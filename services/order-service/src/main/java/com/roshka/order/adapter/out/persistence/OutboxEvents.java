package com.roshka.order.adapter.out.persistence;

import com.roshka.order.application.port.out.Events;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
public class OutboxEvents implements Events {
  private final JdbcClient db;
  private final JsonCodec json;

  public OutboxEvents(JdbcClient db, JsonCodec json) {
    this.db = db;
    this.json = json;
  }

  public void append(String type, UUID id, long version, Object payload) {
    long time = System.currentTimeMillis();
    UUID eventId =
        new UUID(
            (time << 16) | 0x7000L | (ThreadLocalRandom.current().nextLong() & 0x0fffL),
            (ThreadLocalRandom.current().nextLong() & 0x3fffffffffffffffL) | 0x8000000000000000L);
    Map<String, Object> envelope = new LinkedHashMap<>();
    envelope.put("eventId", eventId);
    envelope.put("eventType", type);
    envelope.put("schemaVersion", 1);
    envelope.put("aggregateId", id);
    envelope.put("aggregateVersion", version);
    envelope.put("occurredAt", Instant.now().toString());
    var context = com.roshka.order.configuration.MessageContext.current();
    envelope.put("correlationId", context.correlationId());
    envelope.put("causationId", context.causationId());
    envelope.put("traceparent", context.traceparent());
    envelope.put("producer", "order-service");
    envelope.put("payload", payload);
    db.sql("INSERT INTO message_outbox(event_id,event_type,body) VALUES (:id,:type,:body)").param("id", eventId)
        .param("type", type).param("body", json.write(envelope)).update();
  }
}
