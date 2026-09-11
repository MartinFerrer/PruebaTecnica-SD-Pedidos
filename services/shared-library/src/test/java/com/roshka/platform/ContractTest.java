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
	void documentsAndEmbeddedSchemasValidateAgainstOfficialMetaschemas() throws Exception {
		var registry = com.networknt.schema.SchemaRegistry.withDefaultDialect(
				com.networknt.schema.SpecificationVersion.DRAFT_2020_12);
		var openapi = registry.getSchema(read("contracts/metaschemas/openapi-3.1.json"));
		for (String name : new String[] { "order", "inventory" }) {
			var contract = read("contracts/openapi/" + name + ".json");
			assertThat(openapi.validate(contract)).isEmpty();
			validateEmbeddedSchemas(contract, registry);
			var schemaMeta = registry.getSchema(com.networknt.schema.SchemaLocation.of(
					"https://json-schema.org/draft/2020-12/schema"));
			contract.path("components").path("schemas").forEach(schema ->
				assertThat(schemaMeta.validate(schema)).isEmpty());
			var broken = (tools.jackson.databind.node.ObjectNode) contract.deepCopy();
			broken.put("paths", 3);
			assertThat(openapi.validate(broken)).isNotEmpty();
		}
		var asyncapi = registry.getSchema(read("contracts/metaschemas/asyncapi-3.0.0.json"));
		var events = read("contracts/asyncapi/events.json");
		assertThat(asyncapi.validate(events)).isEmpty();
		var broken = (tools.jackson.databind.node.ObjectNode) events.deepCopy();
		broken.put("channels", 3);
		assertThat(asyncapi.validate(broken)).isNotEmpty();
	}

	private void validateEmbeddedSchemas(JsonNode node, com.networknt.schema.SchemaRegistry registry) {
		if (node.isObject()) {
			for (var property : node.properties()) {
				if (property.getKey().equals("schema")) {
					var meta = registry.getSchema(com.networknt.schema.SchemaLocation.of(
							"https://json-schema.org/draft/2020-12/schema"));
					assertThat(meta.validate(property.getValue())).isEmpty();
				}
				validateEmbeddedSchemas(property.getValue(), registry);
			}
		}
		else if (node.isArray()) {
			node.forEach(child -> validateEmbeddedSchemas(child, registry));
		}
	}

	@Test
	void inventoryContractExposesOnlyTheSingularRestockPath() throws Exception {
		JsonNode contract = read("contracts/openapi/inventory.json");

		assertThat(contract.path("openapi").stringValue()).startsWith("3.");
		assertThat(contract.path("paths").has("/products/{productId}/restock")).isTrue();
		assertThat(contract.path("paths").has("/products/{productId}/restocks")).isFalse();
	}

	@Test
	void documentedLimitsMatchTransportConfiguration() throws Exception {
		for (String service : new String[] { "order", "inventory" }) {
			var limits = read("contracts/openapi/" + service + ".json").path("x-runtime-limits");
			assertThat(limits.toString()).contains("65536", "262144");
			assertThat(com.roshka.platform.web.RequestSizeFilter.MAX_BODY_BYTES).isEqualTo(65536);
			var properties = Files.readString(Path.of("../" + service
					+ "-service/src/main/resources/application.properties"));
			assertThat(properties).contains("server.tomcat.connection-timeout=5s",
					"spring.rabbitmq.connection-timeout=5s");
		}
		assertThat(read("contracts/asyncapi/events.json").path("x-max-message-bytes").asInt()).isEqualTo(262144);
	}

	@Test
	void asynchronousContractDefinesEveryBusinessEvent() throws Exception {
		JsonNode messages = read("contracts/asyncapi/events.json").path("components").path("messages");

		assertThat(messages.propertyNames()).containsExactlyInAnyOrder(
				"OrderCreated", "OrderCancelled", "StockReserved", "StockRejected", "StockReleased",
				"ProductStockCreated", "ProductStockReplenished", "ProductStockUpdated");
		for (String name : new String[] { "StockReserved", "StockRejected", "StockReleased" }) {
			assertThat(messages.path(name).path("payload").path("required").toString()).contains(
				"eventId", "aggregateId", "aggregateVersion", "payload");
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
