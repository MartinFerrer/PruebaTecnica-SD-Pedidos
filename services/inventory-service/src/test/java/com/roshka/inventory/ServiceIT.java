package com.roshka.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class ServiceIT {

	@Test
	void listsAllProducts() throws Exception {
		String first = createProduct(3);
		String second = createProduct(7);

		var response = request("GET", "/products", "list-" + UUID.randomUUID(), null);

		assertThat(response.statusCode()).isEqualTo(200);
		var products = JSON.readTree(response.body());
		assertThat(products.toString()).contains(first, second);
		assertThat(products.findValue("sku")).isNotNull();
	}

	@Test
	void rejectsNonUuidProductIdForStockLookup() throws Exception {
		var response = request("GET", "/products/0/stock", "invalid-id-" + UUID.randomUUID(), null);

		assertThat(response.statusCode()).isEqualTo(400);
		assertThat(response.body()).contains("INVALID_REQUEST");
	}

	@Test
	void propagatesHttpTraceToTransactionalOutbox() throws Exception {
		String key = UUID.randomUUID().toString();
		String trace = "00-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa-bbbbbbbbbbbbbbbb-01";
		var response = HTTP.send(HttpRequest.newBuilder(URI.create(base + "/products"))
			.header("Content-Type", "application/json")
			.header("Idempotency-Key", key)
			.header("traceparent", trace)
			.header("X-Correlation-Id", key)
			.POST(HttpRequest.BodyPublishers
				.ofString("{\"sku\":\"" + key + "\",\"name\":\"Trace\",\"initialStock\":1}"))
			.build(), HttpResponse.BodyHandlers.ofString());
		assertThat(response.statusCode()).isEqualTo(201);
		String id = JSON.readTree(response.body()).get("productId").stringValue();
		var db = app.getBean(org.springframework.jdbc.core.simple.JdbcClient.class);
		String body = db.sql("SELECT body FROM message_outbox WHERE body LIKE :id")
			.param("id", "%" + id + "%")
			.query(String.class)
			.single();
		assertThat(JSON.readTree(body).get("traceparent").stringValue()).isEqualTo(trace);
		assertThat(JSON.readTree(body).get("correlationId").stringValue()).isEqualTo(key);
	}

	@Test
	void concurrentRestocksAreAdditiveAndMovementsAreUnique() throws Exception {
		String id = createProduct(20);
		String movement = UUID.randomUUID().toString();
		try (var pool = java.util.concurrent.Executors.newFixedThreadPool(8)) {
			var start = new java.util.concurrent.CountDownLatch(1);
			var tasks = new java.util.ArrayList<java.util.concurrent.Future<HttpResponse<String>>>();
			for (int i = 0; i < 20; i++) {
				tasks.add(pool.submit(() -> {
					start.await();
					return request("POST", "/products/" + id + "/restock", UUID.randomUUID().toString(),
							"{\"movementId\":\"" + movement + "\",\"quantity\":5,\"reason\":\"RACE\"}");
				}));
			}
			start.countDown();
			for (var task : tasks) {
				assertThat(task.get().statusCode()).isEqualTo(201);
			}
		}
		assertThat(stock(id).get("onHand").asLong()).isEqualTo(25);
		var extra = request("POST", "/products/" + id + "/restock", UUID.randomUUID().toString(),
				"{\"movementId\":\"" + UUID.randomUUID() + "\",\"quantity\":7,\"reason\":\"RACE\"}");
		assertThat(extra.statusCode()).isEqualTo(201);
		assertThat(stock(id).get("onHand").asLong()).isEqualTo(32);
	}

	@Test
	void concurrentRecountsWithTheSameVersionHaveOneWinner() throws Exception {
		String id = createProduct(20);
		try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
			var start = new java.util.concurrent.CountDownLatch(1);
			var first = pool.submit(() -> {
				start.await();
				return request("PUT", "/products", UUID.randomUUID().toString(),
						"{\"productId\":\"" + id + "\",\"stock\":25,\"expectedVersion\":1,\"reason\":\"COUNT_A\"}");
			});
			var second = pool.submit(() -> {
				start.await();
				return request("PUT", "/products", UUID.randomUUID().toString(),
						"{\"productId\":\"" + id + "\",\"stock\":30,\"expectedVersion\":1,\"reason\":\"COUNT_B\"}");
			});
			start.countDown();

			var responses = java.util.List.of(first.get(), second.get());
			assertThat(responses).extracting(HttpResponse::statusCode).containsExactlyInAnyOrder(200, 409);
		}

		var current = stock(id);
		assertThat(current.get("onHand").asLong()).isIn(25L, 30L);
		assertThat(current.get("version").asLong()).isEqualTo(2);
	}

	@Test
	void oneMovementIdCannotRestockTwoProductsConcurrently() throws Exception {
		String firstId = createProduct(10);
		String secondId = createProduct(10);
		String movementId = UUID.randomUUID().toString();
		String body = "{\"movementId\":\"" + movementId + "\",\"quantity\":5,\"reason\":\"DELIVERY\"}";
		try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
			var start = new java.util.concurrent.CountDownLatch(1);
			var first = pool.submit(() -> {
				start.await();
				return request("POST", "/products/" + firstId + "/restock", UUID.randomUUID().toString(), body);
			});
			var second = pool.submit(() -> {
				start.await();
				return request("POST", "/products/" + secondId + "/restock", UUID.randomUUID().toString(), body);
			});
			start.countDown();

			var responses = java.util.List.of(first.get(), second.get());
			assertThat(responses).extracting(HttpResponse::statusCode).containsExactlyInAnyOrder(201, 409);
		}

		assertThat(stock(firstId).get("onHand").asLong() + stock(secondId).get("onHand").asLong()).isEqualTo(25);
		var db = app.getBean(org.springframework.jdbc.core.simple.JdbcClient.class);
		assertThat(db.sql("SELECT count(*) FROM stock_movements WHERE movement_id=CAST(:id AS uuid)")
			.param("id", movementId)
			.query(Long.class)
			.single()).isEqualTo(1);
	}

	@Test
	void stockMutationAndOutboxRollBackTogether() throws Exception {
		String id = createProduct(10);
		var tx = app.getBean(com.roshka.platform.web.RequestTransactions.class);
		var service = app.getBean(com.roshka.inventory.application.port.in.RestockProductUseCase.class);
		UUID movement = UUID.randomUUID();
		assertThatThrownBy(() -> tx.read(() -> {
			service.restock(UUID.fromString(id),
					new com.roshka.inventory.application.port.in.RestockProductUseCase.Command(movement, 5,
							"ROLLBACK"));
			throw new IllegalStateException("INJECTED_BEFORE_COMMIT");
		})).isInstanceOf(IllegalStateException.class);
		assertThat(stock(id).get("onHand").asLong()).isEqualTo(10);
		var db = app.getBean(org.springframework.jdbc.core.simple.JdbcClient.class);
		assertThat(db.sql("SELECT count(*) FROM stock_movements WHERE movement_id=:id")
			.param("id", movement)
			.query(Long.class)
			.single()).isZero();
		assertThat(db.sql("SELECT count(*) FROM message_outbox WHERE body LIKE :id")
			.param("id", "%" + movement + "%")
			.query(Long.class)
			.single()).isZero();
	}

	@Test
	void manualReplayAfterCommitDoesNotReserveAgain() throws Exception {
		String id = createProduct(10);
		String order = UUID.randomUUID().toString();
		String event = UUID.randomUUID().toString();
		Object payload = java.util.Map.of("orderId", order, "items",
				java.util.List.of(java.util.Map.of("productId", id, "quantity", 2)));
		send("OrderCreated", order, 1, event, payload);
		org.awaitility.Awaitility.await()
			.atMost(Duration.ofSeconds(10))
			.untilAsserted(() -> assertThat(stock(id).get("reserved").asLong()).isEqualTo(2));
		send("OrderCreated", order, 1, event, payload);
		org.awaitility.Awaitility.await()
			.during(Duration.ofMillis(500))
			.atMost(Duration.ofSeconds(5))
			.untilAsserted(() -> assertThat(stock(id).get("reserved").asLong()).isEqualTo(2));
	}

	@Test
	void malformedEventGoesToDlqAndDoesNotClaimInbox() {
		var rabbit = app.getBean(org.springframework.amqp.rabbit.core.RabbitTemplate.class);
		rabbit.convertAndSend("", "inventory.in", "{\"schemaVersion\":99}");
		org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
			var failed = rabbit.receive("inventory.dlq");
			assertThat(failed).isNotNull();
			assertThat(new String(failed.getBody(), java.nio.charset.StandardCharsets.UTF_8)).contains("99");
		});
	}

	@Test
	void duplicateReservationAndCancellationConverge() throws Exception {
		String id = createProduct(10);
		String order = UUID.randomUUID().toString();
		String eventId = UUID.randomUUID().toString();
		var items = java.util.List.of(java.util.Map.of("productId", id, "quantity", 4));
		send("OrderCreated", order, 1, eventId, java.util.Map.of("orderId", order, "items", items));
		send("OrderCreated", order, 1, eventId, java.util.Map.of("orderId", order, "items", items));
		org.awaitility.Awaitility.await()
			.atMost(Duration.ofSeconds(10))
			.untilAsserted(() -> assertThat(stock(id).get("reserved").asLong()).isEqualTo(4));
		send("OrderCancelled", order, 2, UUID.randomUUID().toString(),
				java.util.Map.of("orderId", order, "reason", "TEST"));
		org.awaitility.Awaitility.await()
			.atMost(Duration.ofSeconds(10))
			.untilAsserted(() -> assertThat(stock(id).get("reserved").asLong()).isZero());
		send("OrderCreated", order, 1, UUID.randomUUID().toString(),
				java.util.Map.of("orderId", order, "items", items));
		org.awaitility.Awaitility.await()
			.during(Duration.ofMillis(500))
			.atMost(Duration.ofSeconds(5))
			.untilAsserted(() -> assertThat(stock(id).get("reserved").asLong()).isZero());
	}

	@Test
	void cancellationBeforeCreationNeverReserves() throws Exception {
		String id = createProduct(10);
		String order = UUID.randomUUID().toString();
		send("OrderCancelled", order, 2, UUID.randomUUID().toString(), java.util.Map.of("orderId", order));
		send("OrderCreated", order, 1, UUID.randomUUID().toString(), java.util.Map.of("orderId", order, "items",
				java.util.List.of(java.util.Map.of("productId", id, "quantity", 4))));
		var db = app.getBean(org.springframework.jdbc.core.simple.JdbcClient.class);
		org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
			assertThat(db.sql("SELECT state FROM reservations WHERE order_id=CAST(:id AS uuid)")
				.param("id", order)
				.query(String.class)
				.optional()).contains("CANCELLED_BEFORE_RESERVATION");
			assertThat(stock(id).get("reserved").asLong()).isZero();
		});
	}

	@Test
	void allOrNothingReportsEveryShortage() throws Exception {
		String available = createProduct(10);
		String empty = createProduct(0);
		String missing = UUID.randomUUID().toString();
		String order = UUID.randomUUID().toString();
		send("OrderCreated", order, 1, UUID.randomUUID().toString(),
				java.util.Map.of("orderId", order, "items",
						java.util.List.of(java.util.Map.of("productId", available, "quantity", 1),
								java.util.Map.of("productId", empty, "quantity", 2),
								java.util.Map.of("productId", missing, "quantity", 1))));
		var db = app.getBean(org.springframework.jdbc.core.simple.JdbcClient.class);
		org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
			var found = db.sql("SELECT body FROM message_outbox WHERE event_type='StockRejected' AND body LIKE :id")
				.param("id", "%" + order + "%")
				.query(String.class)
				.optional();
			assertThat(found).isPresent();
			String result = found.orElseThrow();
			assertThat(JSON.readTree(result).get("payload").get("unavailableItems").size()).isEqualTo(2);
			assertThat(stock(available).get("reserved").asLong()).isZero();
		});
	}

	static String createProduct(long quantity) throws Exception {
		String key = UUID.randomUUID().toString();
		var response = request("POST", "/products", key,
				"{\"sku\":\"" + key + "\",\"name\":\"Test\",\"initialStock\":" + quantity + "}");
		assertThat(response.statusCode()).isEqualTo(201);
		return JSON.readTree(response.body()).get("productId").stringValue();
	}

	static JsonNode stock(String id) throws Exception {
		return JSON.readTree(request("GET", "/products/" + id + "/stock", "read", null).body());
	}

	static void send(String type, String order, long version, String eventId, Object payload) {
		String body = JSON.writeValueAsString(java.util.Map.ofEntries(java.util.Map.entry("eventId", eventId),
				java.util.Map.entry("eventType", type), java.util.Map.entry("schemaVersion", 1),
				java.util.Map.entry("aggregateId", order), java.util.Map.entry("aggregateVersion", version),
				java.util.Map.entry("occurredAt", java.time.Instant.now().toString()),
				java.util.Map.entry("producer", "order-service"), java.util.Map.entry("correlationId", order),
				java.util.Map.entry("causationId", eventId),
				java.util.Map.entry("traceparent", "00-11111111111111111111111111111111-1111111111111111-01"),
				java.util.Map.entry("payload", payload)));
		app.getBean(org.springframework.amqp.rabbit.core.RabbitTemplate.class).convertAndSend("", "inventory.in", body);
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
			app.close();
		}
		BROKER.stop();
		DB.stop();
	}

	static HttpResponse<String> request(String method, String path, String key, String body) throws Exception {
		return HTTP.send(HttpRequest.newBuilder(URI.create(base + path))
			.timeout(Duration.ofSeconds(15))
			.header("Content-Type", "application/json")
			.header("Idempotency-Key", key)
			.method(method,
					body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
			.build(), HttpResponse.BodyHandlers.ofString());
	}

	@Test
	void createReplayRestockAndVersionedRecount() throws Exception {
		String key = UUID.randomUUID().toString();
		String body = "{\"sku\":\"" + key + "\",\"name\":\"Keyboard\",\"initialStock\":20}";
		var created = request("POST", "/products", key, body);
		assertThat(created.statusCode()).isEqualTo(201);
		assertThat(request("POST", "/products", key, body).body()).isEqualTo(created.body());
		String id = JSON.readTree(created.body()).get("productId").stringValue();
		String movement = "{\"movementId\":\"" + UUID.randomUUID() + "\",\"quantity\":5,\"reason\":\"DELIVERY\"}";
		var restock = request("POST", "/products/" + id + "/restock", UUID.randomUUID().toString(), movement);
		assertThat(restock.statusCode()).isEqualTo(201);
		assertThat(request("POST", "/products/" + id + "/restock", UUID.randomUUID().toString(), movement).body())
			.isEqualTo(restock.body());
		var stale = request("PUT", "/products", UUID.randomUUID().toString(),
				"{\"productId\":\"" + id + "\",\"stock\":20,\"expectedVersion\":1,\"reason\":\"RECOUNT\"}");
		assertThat(stale.statusCode()).isEqualTo(409);
		var current = JSON.readTree(request("GET", "/products/" + id + "/stock", key, null).body());
		assertThat(current.get("onHand").asLong()).isEqualTo(25);
		assertThat(current.get("version").asLong()).isEqualTo(2);
	}

}
