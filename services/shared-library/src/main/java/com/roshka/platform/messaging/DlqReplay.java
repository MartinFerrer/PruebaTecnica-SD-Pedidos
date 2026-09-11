package com.roshka.platform.messaging;

import com.rabbitmq.client.Channel;
import com.roshka.platform.json.JsonCodec;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.function.Predicate;
import tools.jackson.databind.JsonNode;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.support.DefaultMessagePropertiesConverter;

/** Replays one explicitly selected DLQ head; failure leaves the original recoverable. */
public final class DlqReplay {

	private final ConfirmedPublisher publisher;

	public DlqReplay(ConfirmedPublisher publisher) {
		this.publisher = publisher;
	}

	public void replayOne(Channel channel, String service, String expectedEventId, Predicate<JsonNode> converged)
			throws Exception {
		if (!Set.of("order", "inventory").contains(service)) {
			throw new IllegalArgumentException("Unknown destination service");
		}
		var original = channel.basicGet(service + ".dlq", false);
		if (original == null) {
			throw new IllegalStateException("DLQ_EMPTY");
		}
		long tag = original.getEnvelope().getDeliveryTag();
		try {
			var event = new JsonCodec().mapper.readTree(original.getBody());
			EventContract.validate(event);
			String type = event.path("eventType").asString();
			var allowed = service.equals("inventory") ? Set.of("OrderCreated", "OrderCancelled")
													  : Set.of("StockReserved", "StockRejected", "StockReleased");
			if (!expectedEventId.equals(event.path("eventId").asString()) || !allowed.contains(type)) {
				throw new IllegalArgumentException("UNEXPECTED_DLQ_HEAD_OR_DESTINATION");
			}
			var props = new DefaultMessagePropertiesConverter().toMessageProperties(
					original.getProps(), original.getEnvelope(), StandardCharsets.UTF_8.name());
			props.setMessageId(expectedEventId);
			props.setHeader("retry-attempt", 0);
			props.setHeader("replayed", true);
			props.setContentType("application/json");
			props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
			for (String name : Set.of("traceparent", "correlationId", "causationId")) {
				props.setHeader(name, event.path(name).asString());
			}
			publisher.send("", service + ".in", new Message(original.getBody(), props));
			if (!converged.test(event)) {
				throw new IllegalStateException("CONVERGENCE_NOT_VERIFIED_ORIGINAL_RETAINED");
			}
			channel.basicAck(tag, false);
		}
		catch (Exception failure) {
			if (channel.isOpen()) {
				channel.basicNack(tag, false, true);
			}
			throw failure;
		}
	}
}
