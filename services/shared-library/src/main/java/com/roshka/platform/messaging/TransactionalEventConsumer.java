package com.roshka.platform.messaging;

import com.rabbitmq.client.Channel;
import com.roshka.platform.json.JsonCodec;
import java.io.IOException;
import java.util.UUID;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * Shared delivery mechanics for service-specific AMQP consumers.
 *
 * <p>
 * The callback remains in each service so that this technical module never depends on a
 * domain model. The class owns only envelope validation, inbox registration, context
 * propagation and retry/DLQ transfer.
 */
public abstract class TransactionalEventConsumer {

	private static final int[] RETRY_DELAYS_SECONDS = { 1, 5, 30 };

	private final JsonCodec json;

	private final JdbcClient db;

	private final TransactionTemplate tx;

	private final ConfirmedPublisher publisher;

	private final String consumerName;

	private final String inputQueue;

	private final String retryPrefix;

	private final String deadLetterQueue;

	private final String expectedProducer;

	protected TransactionalEventConsumer(JsonCodec json, JdbcClient db, PlatformTransactionManager manager,
			ConfirmedPublisher publisher, String consumerName, String inputQueue, String retryPrefix,
			String deadLetterQueue, String expectedProducer) {
		this.json = json;
		this.db = db;
		this.tx = new TransactionTemplate(manager);
		this.publisher = publisher;
		this.consumerName = consumerName;
		this.inputQueue = inputQueue;
		this.retryPrefix = retryPrefix;
		this.deadLetterQueue = deadLetterQueue;
		this.expectedProducer = expectedProducer;
		this.tx.setTimeout(15);
	}

	protected final JsonCodec json() {
		return json;
	}

	protected final void consume(Message message, Channel channel) throws IOException {
		long tag = message.getMessageProperties().getDeliveryTag();
		try {
			JsonNode event = json.mapper.readTree(message.getBody());
			validate(event);
			tx.executeWithoutResult(status -> {
				db.sql("SET LOCAL lock_timeout = '3s'").update();
				int added = db
					.sql("INSERT INTO message_inbox(consumer_name,event_id) VALUES (:consumer,:id) "
							+ "ON CONFLICT DO NOTHING")
					.param("consumer", consumerName)
					.param("id", UUID.fromString(requiredText(event, "eventId")))
					.update();
				if (added != 0) {
					var context = new MessageContext(requiredText(event, "correlationId"),
							requiredText(event, "eventId"), requiredText(event, "traceparent"));
					try (var scope = context.open()) {
						handle(event);
					}
				}
			});
		}
		catch (RuntimeException e) {
			transferFailure(message, channel, e);
			return;
		}
		channel.basicAck(tag, false);
	}

	protected abstract void handle(JsonNode event);

	protected boolean isPermanentFailure(RuntimeException failure) {
		return failure instanceof IllegalArgumentException || failure instanceof tools.jackson.core.JacksonException;
	}

	private void transferFailure(Message message, Channel channel, RuntimeException failure) throws IOException {
		Object header = message.getMessageProperties().getHeaders().get("retry-attempt");
		int attempt = header instanceof Number n ? Math.clamp(n.intValue(), 0, 3) : 0;
		boolean permanent = isPermanentFailure(failure);
		String destination = permanent || attempt >= RETRY_DELAYS_SECONDS.length ? deadLetterQueue
				: retryPrefix + RETRY_DELAYS_SECONDS[attempt];
		var props = new MessageProperties();
		props.getHeaders().putAll(message.getMessageProperties().getHeaders());
		props.setHeader("retry-attempt", attempt + 1);
		props.setHeader("failure", failure.getClass().getSimpleName());
		props.setHeader("original-queue", inputQueue);
		props.setMessageId(message.getMessageProperties().getMessageId());
		props.setContentType("application/json");
		props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
		try {
			publisher.send("", destination, new Message(message.getBody(), props));
		}
		catch (RuntimeException transferFailure) {
			channel.abort();
			throw transferFailure;
		}
		channel.basicAck(message.getMessageProperties().getDeliveryTag(), false);
	}

	private void validate(JsonNode event) {
		if (!event.isObject() || !event.hasNonNull("eventId") || !event.hasNonNull("aggregateId")
				|| !event.hasNonNull("eventType") || !event.hasNonNull("payload")
				|| event.path("schemaVersion").asInt() != 1 || event.path("aggregateVersion").asLong() < 1
				|| !expectedProducer.equals(event.path("producer").stringValue())) {
			throw new IllegalArgumentException("INVALID_ENVELOPE");
		}
		UUID.fromString(requiredText(event, "eventId"));
		UUID.fromString(requiredText(event, "aggregateId"));
		if (!requiredText(event, "traceparent").matches("00-(?!0{32})[0-9a-f]{32}-(?!0{16})[0-9a-f]{16}-[0-9a-f]{2}")
				|| requiredText(event, "correlationId").isBlank() || requiredText(event, "causationId").isBlank()) {
			throw new IllegalArgumentException("INVALID_CONTEXT");
		}
		if (!requiredText(event, "aggregateId").equals(requiredText(event.get("payload"), "orderId"))) {
			throw new IllegalArgumentException("ORDER_ID_MISMATCH");
		}
	}

	protected static JsonNode required(JsonNode node, String field) {
		JsonNode value = node.get(field);
		if (value == null || value.isNull()) {
			throw new IllegalArgumentException("MISSING_" + field);
		}
		return value;
	}

	protected static String requiredText(JsonNode node, String field) {
		String value = required(node, field).stringValue();
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException("INVALID_" + field);
		}
		return value;
	}

}
