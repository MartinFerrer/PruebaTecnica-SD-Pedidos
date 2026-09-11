package com.roshka.platform.messaging;

import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import com.roshka.platform.json.JsonCodec;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/** Validates the versioned wire envelope, without sharing service domain models. */
public final class EventContract {

	private static final Map<String, Schema> SCHEMAS = load();

	private EventContract() {
	}

	public static void validate(JsonNode event) {
		Schema schema = SCHEMAS.get(event.path("eventType").asString());
		if (schema == null || !schema.validate(event,
				context -> context.executionConfig(config -> config.formatAssertionsEnabled(true))).isEmpty()) {
			throw new IllegalArgumentException("INVALID_EVENT_CONTRACT");
		}
	}

	private static Map<String, Schema> load() {
		try (var input = EventContract.class.getResourceAsStream("/contracts/asyncapi/events.json")) {
			if (input == null) {
				throw new IllegalStateException("Missing AsyncAPI contract");
			}
			var messages = new JsonCodec().mapper.readTree(input).path("components").path("messages");
			var registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_7);
			var schemas = new HashMap<String, Schema>();
			messages.properties().forEach(entry -> schemas.put(entry.getKey(),
					registry.getSchema(entry.getValue().path("payload"))));
			return Map.copyOf(schemas);
		}
		catch (IOException failure) {
			throw new IllegalStateException("Cannot load AsyncAPI contract", failure);
		}
	}
}
