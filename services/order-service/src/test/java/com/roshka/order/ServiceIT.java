package com.roshka.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import tools.jackson.databind.json.JsonMapper;

class ServiceIT {

	@Test
	void migrationUpgradesHistoricalOrderResponsesWithoutLosingLocation() {
		var source = app.getBean(javax.sql.DataSource.class);
		var migrations = org.flywaydb.core.Flyway.configure().dataSource(source)
			.schemas("upgrade_test").defaultSchema("upgrade_test").locations("classpath:db/migration");
		migrations.target("2").load().migrate();
		var db = app.getBean(org.springframework.jdbc.core.simple.JdbcClient.class);
		UUID id = UUID.randomUUID();
		db.sql("INSERT INTO upgrade_test.http_idempotency VALUES ('create-order','legacy','hash',202,:body)")
			.param("body", "{\"orderId\":\"" + id + "\",\"status\":\"PENDING\"}").update();
		org.flywaydb.core.Flyway.configure().dataSource(source).schemas("upgrade_test")
			.defaultSchema("upgrade_test").locations("classpath:db/migration").load().migrate();
		assertThat(db.sql("SELECT headers->>'Location' FROM upgrade_test.http_idempotency")
			.query(String.class).single()).isEqualTo("/orders/" + id);
	}

	@Test
	void databaseRejectsDuplicateLinesAndQuantityLimits() throws Exception {
		String order = createOrder();
		var db = app.getBean(org.springframework.jdbc.core.simple.JdbcClient.class);
		org.assertj.core.api.Assertions.assertThatThrownBy(() -> db.sql("""
				INSERT INTO order_items SELECT order_id,product_id,quantity FROM order_items WHERE order_id=:id
				""").param("id", UUID.fromString(order)).update())
			.isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
		for (long quantity : new long[] { 0, -1, 1000000001L }) {
			org.assertj.core.api.Assertions.assertThatThrownBy(() -> db.sql(
					"UPDATE order_items SET quantity=:quantity WHERE order_id=:id")
				.param("quantity", quantity).param("id", UUID.fromString(order)).update())
				.isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
		}
	}

	@Test
	void rejectsMalformedUnknownOversizedAndInvalidRequests() throws Exception {
		for (String body : java.util.List.of("", "{", "{}", "{\"items\":[]}",
				"{\"items\":[],\"unknown\":1}",
				"{\"items\":[{\"productId\":\"not-a-uuid\",\"quantity\":1}]}")) {
			assertThat(request("POST", "/orders", UUID.randomUUID().toString(), body).statusCode()).isEqualTo(400);
		}
		assertThat(request("POST", "/orders", UUID.randomUUID().toString(), " ".repeat(65537)).statusCode())
			.isEqualTo(413);
		assertThat(request("GET", "/orders/1-1-1-1-1", "read", null).statusCode()).isEqualTo(400);
		assertThat(request("GET", "/orders/" + UUID.randomUUID(), "read", null).statusCode()).isEqualTo(404);
		assertThat(request("POST", "/orders/" + UUID.randomUUID() + "/cancel", UUID.randomUUID().toString(),
				"{\"reason\":\"" + "a".repeat(201) + "\"}").statusCode()).isEqualTo(400);
	}

	@Test
	void sameKeyOneHundredHttpRequestsHaveOneOrderAndHistoricalHeaders() throws Exception {
		String key = UUID.randomUUID().toString();
		String body = "{\"items\":[{\"productId\":\"" + UUID.randomUUID() + "\",\"quantity\":1}]}";
		var start = new java.util.concurrent.CountDownLatch(1);
		try (var pool = java.util.concurrent.Executors.newFixedThreadPool(16)) {
			var tasks = new java.util.ArrayList<java.util.concurrent.Future<HttpResponse<String>>>();
			for (int i = 0; i < 100; i++) {
				tasks.add(pool.submit(() -> {
					start.await();
					return request("POST", "/orders", key, body);
				}));
			}
			start.countDown();
			var first = tasks.getFirst().get();
			for (var task : tasks) {
				var replay = task.get(30, java.util.concurrent.TimeUnit.SECONDS);
				assertThat(replay.statusCode()).isEqualTo(202);
				assertThat(replay.body()).isEqualTo(first.body());
				for (String header : java.util.List.of("Location", "Content-Type", "X-Correlation-Id")) {
					assertThat(replay.headers().firstValue(header)).isEqualTo(first.headers().firstValue(header));
				}
			}
			var db = app.getBean(org.springframework.jdbc.core.simple.JdbcClient.class);
			String id = JSON.readTree(first.body()).path("orderId").asString();
			assertThat(db.sql("SELECT count(*) FROM message_outbox WHERE event_type='OrderCreated' AND body LIKE :id")
				.param("id", "%" + id + "%").query(Long.class).single()).isEqualTo(1);
		}
	}

	@Test
	void listsAllOrders() throws Exception {
		String first = createOrder();
		String second = createOrder();

		var response = request("GET", "/orders", "list-" + UUID.randomUUID(), null);

		assertThat(response.statusCode()).isEqualTo(200);
		var orders = JSON.readTree(response.body());
		assertThat(orders.toString()).contains(first, second);
	}

	@Test
	void mismatchedReservationResultCannotConfirmOrder() throws Exception {
		String productId = UUID.randomUUID().toString();
		var created = request("POST", "/orders", UUID.randomUUID().toString(),
				"{\"items\":[{\"productId\":\"" + productId + "\",\"quantity\":2}]}");
		String orderId = JSON.readTree(created.body()).get("orderId").stringValue();

		send("StockReserved", orderId, 1, java.util.Map.of("orderId", orderId, "requestOrderVersion", 1, "items",
				java.util.List.of(java.util.Map.of("productId", UUID.randomUUID().toString(), "quantity", 2))));

		var rabbit = app.getBean(org.springframework.amqp.rabbit.core.RabbitTemplate.class);
		org.awaitility.Awaitility.await()
			.atMost(Duration.ofSeconds(10))
			.untilAsserted(() -> assertThat(rabbit.receive("order.dlq")).isNotNull());
		var current = request("GET", "/orders/" + orderId, "read", null);
		assertThat(JSON.readTree(current.body()).get("status").stringValue()).isEqualTo("PENDING");
	}

	@Test
	void mismatchedReleaseCannotCompleteInventoryCancellation() throws Exception {
		String productId = UUID.randomUUID().toString();
		var created = request("POST", "/orders", UUID.randomUUID().toString(),
				"{\"items\":[{\"productId\":\"" + productId + "\",\"quantity\":2}]}");
		String orderId = JSON.readTree(created.body()).get("orderId").stringValue();
		var cancelled = request("POST", "/orders/" + orderId + "/cancel", UUID.randomUUID().toString(), "{}");
		assertThat(cancelled.statusCode()).isEqualTo(202);

		send("StockReleased", orderId, 2,
				java.util.Map.of("orderId", orderId, "requestOrderVersion", 2, "outcome", "RELEASED", "releasedItems",
						java.util.List.of(java.util.Map.of("productId", UUID.randomUUID().toString(), "quantity", 2))));

		var rabbit = app.getBean(org.springframework.amqp.rabbit.core.RabbitTemplate.class);
		org.awaitility.Awaitility.await()
			.atMost(Duration.ofSeconds(10))
			.untilAsserted(() -> assertThat(rabbit.receive("order.dlq")).isNotNull());
		var current = request("GET", "/orders/" + orderId, "read", null);
		var order = JSON.readTree(current.body());
		assertThat(order.get("status").stringValue()).isEqualTo("CANCELLED");
		assertThat(order.get("inventoryCancellationStatus").stringValue()).isEqualTo("PENDING");
	}

	static final PostgreSQLContainer DB = new PostgreSQLContainer("postgres:18.3-alpine");
	static final RabbitMQContainer BROKER = new RabbitMQContainer("rabbitmq:4.2.3-management-alpine");
	static ConfigurableApplicationContext app;
	static String base;
	static final JsonMapper JSON = JsonMapper.builder().build();
	static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

	@BeforeAll
	static void start() {
		DB.start();
		BROKER.start();
		app = SpringApplication.run(Application.class, "--server.port=0", "--spring.datasource.url=" + DB.getJdbcUrl(),
				"--spring.datasource.username=" + DB.getUsername(), "--spring.datasource.password=" + DB.getPassword(),
				"--spring.rabbitmq.host=" + BROKER.getHost(), "--spring.rabbitmq.port=" + BROKER.getAmqpPort(),
				"--spring.rabbitmq.username=" + BROKER.getAdminUsername(),
				"--spring.rabbitmq.password=" + BROKER.getAdminPassword());
		base = "http://localhost:" + app.getEnvironment().getProperty("local.server.port");
	}

	@AfterAll
	static void stop() {
		if (app != null) {
			try {
				app.getBean(org.springframework.jdbc.core.simple.JdbcClient.class)
					.sql("SELECT body FROM message_outbox").query(String.class).list()
					.forEach(body -> com.roshka.platform.messaging.EventContract.validate(JSON.readTree(body)));
			}
			finally {
				app.close();
			}
		}
		BROKER.stop();
		DB.stop();
	}

	static HttpResponse<String> request(String method, String path, String key, String body) throws Exception {
		var response = HTTP.send(HttpRequest.newBuilder(URI.create(base + path))
			.timeout(Duration.ofSeconds(15))
			.header("Content-Type", "application/json")
			.header("Idempotency-Key", key)
			.method(method,
					body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
			.build(), HttpResponse.BodyHandlers.ofString());
		com.roshka.testing.ObservedContract.response("order", method, path, response);
		com.roshka.testing.ObservedContract.request("order", method, path, body, response.statusCode());
		return response;
	}

	@Test
	void createReplayAndCancelArePersistent() throws Exception {
		String key = UUID.randomUUID().toString();
		String body = "{\"items\":[{\"productId\":\"" + UUID.randomUUID() + "\",\"quantity\":2}]}";
		var created = request("POST", "/orders", key, body);
		assertThat(created.statusCode()).isEqualTo(202);
		assertThat(request("POST", "/orders", key, body).body()).isEqualTo(created.body());
		String id = JSON.readTree(created.body()).get("orderId").stringValue();
		var cancelled = request("POST", "/orders/" + id + "/cancel", key, "{}");
		assertThat(cancelled.statusCode()).isEqualTo(202);
		assertThat(JSON.readTree(cancelled.body()).get("status").stringValue()).isEqualTo("CANCELLED");
		assertThat(request("POST", "/orders/" + id + "/cancel", key, "{}").body()).isEqualTo(cancelled.body());
		assertThat(request("POST", "/orders/" + id + "/cancel", UUID.randomUUID().toString(), "{}").statusCode())
			.isEqualTo(200);
		assertThat(JSON.readTree(created.body()).get("statusUrl").stringValue()).isEqualTo("/orders/" + id);
		assertThat(request("POST", "/orders", key,
				"{\"items\":[{\"productId\":\"" + UUID.randomUUID() + "\",\"quantity\":1}]}")
			.statusCode()).isEqualTo(409);
	}

	static String createOrder() throws Exception {
		String id = UUID.randomUUID().toString();
		var response = request("POST", "/orders", UUID.randomUUID().toString(),
				"{\"items\":[{\"productId\":\"" + id + "\",\"quantity\":1}]}");
		assertThat(response.statusCode()).isEqualTo(202);
		return JSON.readTree(response.body()).get("orderId").stringValue();
	}

	static void send(String type, String orderId, long aggregateVersion, Object payload) {
		String eventId = UUID.randomUUID().toString();
		String body = JSON.writeValueAsString(java.util.Map.ofEntries(java.util.Map.entry("eventId", eventId),
				java.util.Map.entry("eventType", type), java.util.Map.entry("schemaVersion", 1),
				java.util.Map.entry("aggregateId", orderId), java.util.Map.entry("aggregateVersion", aggregateVersion),
				java.util.Map.entry("occurredAt", java.time.Instant.now().toString()),
				java.util.Map.entry("producer", "inventory-service"), java.util.Map.entry("correlationId", orderId),
				java.util.Map.entry("causationId", eventId),
				java.util.Map.entry("traceparent", "00-11111111111111111111111111111111-1111111111111111-01"),
				java.util.Map.entry("payload", payload)));
		app.getBean(org.springframework.amqp.rabbit.core.RabbitTemplate.class).convertAndSend("", "order.in", body);
	}

}
