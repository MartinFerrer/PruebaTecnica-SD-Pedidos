package com.roshka.inventory.adapter.out.persistence;

import com.roshka.inventory.application.port.out.EventPublisherPort;
import com.roshka.platform.json.JsonCodec;
import com.roshka.platform.messaging.OutboxWriter;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
public class OutboxEvents implements EventPublisherPort {

	private final OutboxWriter writer;

	public OutboxEvents(JdbcClient db, JsonCodec json) {
		this.writer = new OutboxWriter(db, json, "inventory-service");
	}

	public void publish(String type, UUID id, long version, Object payload) {
		writer.append(type, id, version, payload);
	}

}
