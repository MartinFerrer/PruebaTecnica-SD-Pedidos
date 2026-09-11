package com.roshka.platform.messaging;

import com.roshka.platform.json.JsonCodec;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Writes protocol envelopes to a service-owned transactional outbox table. */
public final class OutboxWriter {

	private final JdbcClient db;

	private final JsonCodec json;

	private final String producer;

	public OutboxWriter(JdbcClient db, JsonCodec json, String producer) {
		this.db = db;
		this.json = json;
		this.producer = producer;
	}

	public void append(String type, UUID aggregateId, long aggregateVersion, Object payload) {
		UUID eventId = newEventId();
		Map<String, Object> envelope = new LinkedHashMap<>();
		envelope.put("eventId", eventId);
		envelope.put("eventType", type);
		envelope.put("schemaVersion", 1);
		envelope.put("aggregateId", aggregateId);
		envelope.put("aggregateVersion", aggregateVersion);
		envelope.put("occurredAt", Instant.now().toString());
		var context = MessageContext.current();
		envelope.put("correlationId", context.correlationId());
		envelope.put("causationId", context.causationId());
		envelope.put("traceparent", context.traceparent());
		envelope.put("producer", producer);
		envelope.put("payload", payload);
		db.sql("INSERT INTO message_outbox(event_id,event_type,body) VALUES (:id,:type,:body)")
			.param("id", eventId)
			.param("type", type)
			.param("body", json.write(envelope))
			.update();
	}

	private static UUID newEventId() {
		long time = System.currentTimeMillis();
		return new UUID((time << 16) | 0x7000L | (ThreadLocalRandom.current().nextLong() & 0x0fffL),
				(ThreadLocalRandom.current().nextLong() & 0x3fffffffffffffffL) | 0x8000000000000000L);
	}

}
