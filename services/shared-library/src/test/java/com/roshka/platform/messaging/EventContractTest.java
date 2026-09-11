package com.roshka.platform.messaging;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.roshka.platform.json.JsonCodec;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

class EventContractTest {

	static final JsonCodec JSON = new JsonCodec();

	static ObjectNode event(String type) {
		String order = UUID.randomUUID().toString();
		String product = UUID.randomUUID().toString();
		var item = Map.of("productId", product, "quantity", 1);
		Map<String, Object> payload = switch (type) {
			case "OrderCreated" -> Map.of("orderId", order, "items", List.of(item));
			case "OrderCancelled" -> Map.of("orderId", order, "reason", "TEST");
			case "StockReserved" -> Map.of("orderId", order, "requestOrderVersion", 1, "items", List.of(item));
			case "StockRejected" -> Map.of("orderId", order, "requestOrderVersion", 1, "unavailableItems",
					List.of(Map.of("productId", product, "requested", 1, "available", 0,
							"reason", "INSUFFICIENT_STOCK")));
			case "StockReleased" -> Map.of("orderId", order, "requestOrderVersion", 2,
					"outcome", "RELEASED", "releasedItems", List.of(item));
			case "ProductStockCreated" -> Map.of("productId", product, "sku", "SKU", "name", "Product",
					"onHand", 2, "reserved", 0, "available", 2, "version", 1, "updatedAt", Instant.now().toString());
			default -> Map.of("productId", product, "movementId", UUID.randomUUID().toString(), "quantity", 1,
					"previousOnHand", 1, "onHand", 2, "reserved", 0, "available", 2, "version", 2, "reason", "TEST");
		};
		return (ObjectNode) JSON.mapper.readTree(JSON.write(Map.ofEntries(
				Map.entry("eventId", UUID.randomUUID().toString()), Map.entry("eventType", type),
				Map.entry("schemaVersion", 1), Map.entry("aggregateId", type.startsWith("Product") ? product : order),
				Map.entry("aggregateVersion", 1), Map.entry("occurredAt", Instant.now().toString()),
				Map.entry("producer", type.startsWith("Order") ? "order-service" : "inventory-service"),
				Map.entry("correlationId", order), Map.entry("causationId", order),
				Map.entry("traceparent", MessageContext.newTrace()), Map.entry("payload", payload))));
	}

	@Test
	void everyEventRejectsBadIdentityVersionDateUnknownFieldsAndMissingPayload() {
		for (String type : List.of("OrderCreated", "OrderCancelled", "StockReserved", "StockRejected",
				"StockReleased", "ProductStockCreated", "ProductStockReplenished", "ProductStockUpdated")) {
			var valid = event(type);
			EventContract.validate(valid);
			for (var mutation : List.of(valid.deepCopy().put("eventId", "1-1-1-1-1"),
					valid.deepCopy().put("schemaVersion", 2), valid.deepCopy().put("occurredAt", "yesterday"),
					valid.deepCopy().put("unknown", true), valid.deepCopy().put("aggregateVersion", 0),
					valid.deepCopy().put("producer", "unknown"))) {
				assertThatThrownBy(() -> EventContract.validate(mutation)).isInstanceOf(IllegalArgumentException.class);
			}
			var missing = valid.deepCopy();
			((ObjectNode) missing.path("payload")).removeAll();
			assertThatThrownBy(() -> EventContract.validate(missing)).isInstanceOf(IllegalArgumentException.class);
			var extra = valid.deepCopy();
			((ObjectNode) extra.path("payload")).put("unknown", true);
			assertThatThrownBy(() -> EventContract.validate(extra)).isInstanceOf(IllegalArgumentException.class);
		}
	}

	@Test
	void enumAndQuantityViolationsAreRejected() {
		var released = event("StockReleased");
		((ObjectNode) released.path("payload")).put("outcome", "UNKNOWN");
		assertThatThrownBy(() -> EventContract.validate(released)).isInstanceOf(IllegalArgumentException.class);
		var created = event("OrderCreated");
		((ObjectNode) created.at("/payload/items/0")).put("quantity", 0);
		assertThatThrownBy(() -> EventContract.validate(created)).isInstanceOf(IllegalArgumentException.class);
	}
}
