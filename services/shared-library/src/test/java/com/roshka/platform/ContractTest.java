package com.roshka.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class ContractTest {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	@Test
	void inventoryContractExposesOnlyTheSingularRestockPath() throws Exception {
		JsonNode contract = read("contracts/openapi/inventory.json");

		assertThat(contract.path("openapi").stringValue()).startsWith("3.");
		assertThat(contract.path("paths").has("/products/{productId}/restock")).isTrue();
		assertThat(contract.path("paths").has("/products/{productId}/restocks")).isFalse();
	}

	@Test
	void asynchronousContractDefinesEveryBusinessEvent() throws Exception {
		JsonNode messages = read("contracts/asyncapi/events.json").path("components").path("messages");

		assertThat(messages.propertyNames()).containsExactlyInAnyOrder(
				"OrderCreated", "OrderCancelled", "StockReserved", "StockRejected", "StockReleased", "ProductStockCreated", "ProductStockReplenished", "ProductStockUpdated");
		for (String name : new String[] { "StockReserved", "StockRejected", "StockReleased" }) {
			assertThat(messages.path(name).path("payload").path("required").toString()).contains("eventId", "aggregateId", "aggregateVersion", "payload");
		}
	}

	private static JsonNode read(String relativePath) throws Exception {
		Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
		while (current != null && !Files.exists(current.resolve(relativePath))) {
			current = current.getParent();
		}
		if (current == null) {
			throw new IllegalStateException("Repository root not found");
		}
		return JSON.readTree(Files.readString(current.resolve(relativePath)));
	}

}
