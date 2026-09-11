package com.roshka.testing;

import static org.assertj.core.api.Assertions.assertThat;

import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import com.roshka.platform.json.JsonCodec;
import java.net.http.HttpResponse;
import tools.jackson.databind.JsonNode;

/** Assertions over real HTTP exchanges, with local references resolved from the complete OpenAPI. */
public final class ObservedContract {

	private static final SchemaRegistry REGISTRY = SchemaRegistry
		.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);

	private ObservedContract() {
	}

	public static void request(String service, String method, String route, String body, int status) {
		if (status >= 400 || body == null) {
			return;
		}
		String resource = "classpath:contracts/openapi/" + service + ".json";
		var document = REGISTRY.getSchema(SchemaLocation.of(resource)).getSchemaNode();
		String path = document.path("paths").propertyNames().stream()
			.filter(pattern -> route.matches(pattern.replaceAll("\\{[^}]+\\}", "[^/]+")))
			.findFirst().orElseThrow();
		String pointer = "/paths/" + escape(path) + "/" + method.toLowerCase()
				+ "/requestBody/content/application~1json/schema";
		assertThat(REGISTRY.getSchema(SchemaLocation.of(resource + "#" + pointer))
			.validate(new JsonCodec().mapper.readTree(body), context -> context.executionConfig(
					config -> config.formatAssertionsEnabled(true)))).as("accepted request matches OpenAPI").isEmpty();
	}

	public static void response(String service, String method, String route, HttpResponse<String> response) {
		String resource = "classpath:contracts/openapi/" + service + ".json";
		JsonNode document = REGISTRY.getSchema(SchemaLocation.of(resource)).getSchemaNode();
		String path = document.path("paths").propertyNames().stream()
			.filter(pattern -> route.matches(pattern.replaceAll("\\{[^}]+\\}", "[^/]+")))
			.findFirst().orElseThrow(() -> new AssertionError("Undocumented path: " + route));
		String operation = "/paths/" + escape(path) + "/" + method.toLowerCase();
		var responses = document.at(operation + "/responses");
		String status = responses.has(String.valueOf(response.statusCode()))
				? String.valueOf(response.statusCode()) : "default";
		var definition = responses.path(status);
		assertThat(definition.isMissingNode()).as("response %s %s %s", method, path, status).isFalse();
		String media = response.headers().firstValue("Content-Type").orElseThrow().split(";")[0];
		assertThat(definition.path("content").has(media)).as("documented content type").isTrue();
		String schemaPath = operation + "/responses/" + status + "/content/" + escape(media) + "/schema";
		var schema = REGISTRY.getSchema(SchemaLocation.of(resource + "#" + schemaPath));
		assertThat(schema.validate(new JsonCodec().mapper.readTree(response.body()),
				context -> context.executionConfig(config -> config.formatAssertionsEnabled(true))))
			.as("response matches OpenAPI: %s %s", method, path).isEmpty();
		assertThat(response.headers().firstValue("X-Correlation-Id")).isPresent();
		for (var header : definition.path("headers").properties()) {
			var value = response.headers().firstValue(header.getKey());
			if (header.getValue().path("required").asBoolean()) {
				assertThat(value).as("required header %s", header.getKey()).isPresent();
			}
			value.ifPresent(actual -> assertThat(REGISTRY.getSchema(header.getValue().path("schema"))
				.validate(new JsonCodec().mapper.valueToTree(actual))).as("header %s", header.getKey()).isEmpty());
		}
		if (service.equals("order") && method.equals("POST") && path.equals("/orders")
				&& response.statusCode() == 202) {
			assertThat(response.headers().firstValue("Location")).contains(
					"/orders/" + new JsonCodec().mapper.readTree(response.body()).path("orderId").asString());
		}
		if (response.statusCode() == 503) {
			assertThat(response.headers().firstValue("Retry-After")).contains("1");
		}
	}

	private static String escape(String value) {
		return value.replace("~", "~0").replace("/", "~1");
	}
}
