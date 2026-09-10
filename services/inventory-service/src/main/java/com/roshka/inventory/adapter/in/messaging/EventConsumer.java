package com.roshka.inventory.adapter.in.messaging;

import com.rabbitmq.client.Channel;
import com.roshka.inventory.adapter.out.messaging.ConfirmedPublisher;
import com.roshka.inventory.adapter.out.persistence.JsonCodec;
import com.roshka.inventory.domain.BusinessException;
import java.util.*;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

@Component
public class EventConsumer {
  private final JsonCodec json;
  private final JdbcClient db;
  private final TransactionTemplate tx;
  private final ConfirmedPublisher publisher;
  private final com.roshka.inventory.application.port.in.Reservations service;

  public EventConsumer(
      JsonCodec json,
      JdbcClient db,
      PlatformTransactionManager manager,
      ConfirmedPublisher publisher,
      com.roshka.inventory.application.port.in.Reservations service) {
    this.json = json;
    this.db = db;
    this.tx = new TransactionTemplate(manager);
    this.publisher = publisher;
    this.service = service;
    this.tx.setTimeout(15);
  }

  @RabbitListener(queues = "inventory.in")
  public void receive(Message message, Channel channel) throws java.io.IOException {
    long tag = message.getMessageProperties().getDeliveryTag();
    try {
      JsonNode event = json.mapper.readTree(message.getBody());
      validate(event);
      tx.executeWithoutResult(
          status -> {
            db.sql("SET LOCAL lock_timeout = '3s'").update();
            int added =
                db.sql(
                        "INSERT INTO message_inbox(consumer_name,event_id) VALUES ('inventory',:id) ON CONFLICT DO NOTHING")
                    .param("id", UUID.fromString(event.get("eventId").asText())).update();
            if (added != 0) {
              var context =
                  new com.roshka.inventory.configuration.MessageContext(
                      event.path("correlationId").asText(),
                      event.get("eventId").asText(),
                      event.path("traceparent").asText());
              try (var scope = context.open()) {
                handle(event);
              }
            }
          });
    } catch (RuntimeException e) {
      Object header = message.getMessageProperties().getHeaders().get("retry-attempt");
      int attempt = header instanceof Number n ? Math.max(0, Math.min(3, n.intValue())) : 0;
      boolean permanent =
          e instanceof IllegalArgumentException
              || e instanceof BusinessException
              || e instanceof tools.jackson.core.JacksonException;
      String destination =
          permanent || attempt >= 3
              ? "inventory.dlq"
              : "inventory.retry." + new int[] {1, 5, 30}[attempt];
      var props = new MessageProperties();
      props.getHeaders().putAll(message.getMessageProperties().getHeaders());
      props.setHeader("retry-attempt", attempt + 1);
      props.setHeader("failure", e.getClass().getSimpleName());
      props.setHeader("original-queue", "inventory.in");
      props.setMessageId(message.getMessageProperties().getMessageId());
      props.setContentType("application/json");
      props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
      try {
        publisher.send("", destination, new Message(message.getBody(), props));
      } catch (RuntimeException transferFailure) {
        channel.abort();
        throw transferFailure;
      }
      channel.basicAck(tag, false);
      return;
    }
    channel.basicAck(tag, false);
  }

  private void validate(JsonNode e) {
    if (!e.isObject()
        || !e.hasNonNull("eventId")
        || !e.hasNonNull("aggregateId")
        || !e.hasNonNull("eventType")
        || !e.hasNonNull("payload")
        || e.path("schemaVersion").asInt() != 1
        || e.path("aggregateVersion").asLong() < 1
        || !"order-service".equals(e.path("producer").asText()))
      throw new IllegalArgumentException("INVALID_ENVELOPE");
    UUID.fromString(e.get("eventId").asText());
    UUID.fromString(e.get("aggregateId").asText());
    if (!e.path("traceparent").asText().matches("00-(?!0{32})[0-9a-f]{32}-(?!0{16})[0-9a-f]{16}-[0-9a-f]{2}")
        || e.path("correlationId").asText().isBlank()
        || e.path("causationId").asText().isBlank())
      throw new IllegalArgumentException("INVALID_CONTEXT");
    if (!e.get("aggregateId").asText().equals(e.get("payload").path("orderId").asText()))
      throw new IllegalArgumentException("ORDER_ID_MISMATCH");
  }

  private void handle(JsonNode e) {
    UUID id = UUID.fromString(e.get("aggregateId").asText());
    JsonNode p = e.get("payload");
    String type = e.get("eventType").asText();
    long version = e.get("aggregateVersion").asLong();
    switch (type) {
      case "OrderCreated" ->
          service.reserve(
              id,
              version,
              List.of(
                  json.mapper.treeToValue(
                      p.get("items"), com.roshka.inventory.domain.Reservation.Item[].class)));
      case "OrderCancelled" -> service.cancel(id, version);
      default -> throw new IllegalArgumentException("UNSUPPORTED_EVENT");
    }
  }
}
