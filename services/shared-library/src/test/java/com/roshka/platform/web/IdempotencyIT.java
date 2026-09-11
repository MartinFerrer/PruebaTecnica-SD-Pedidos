package com.roshka.platform.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.roshka.platform.json.JsonCodec;
import com.roshka.platform.messaging.MessageContext;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.testcontainers.postgresql.PostgreSQLContainer;

class IdempotencyIT {

	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.3-alpine");

	static DriverManagerDataSource source;

	static JdbcClient db;

	static IdempotencyExecutor executor;

	@BeforeAll
	static void start() {
		POSTGRES.start();
		source = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
		db = JdbcClient.create(source);
		Path migrations = Path.of("../inventory-service/src/main/resources/db/migration").toAbsolutePath();
		Flyway.configure().dataSource(source).locations("filesystem:" + migrations).target("2").load().migrate();
		db.sql("INSERT INTO http_idempotency VALUES ('legacy','legacy','hash',200,'{}')").update();
		Flyway.configure().dataSource(source).locations("filesystem:" + migrations).load().migrate();
		executor = new IdempotencyExecutor(db, new JsonCodec(), new JdbcTransactionManager(source));
	}

	@AfterAll
	static void stop() {
		POSTGRES.stop();
	}

	@Test
	void upgradePreservesExistingRowsAndAddsHeaders() {
		assertThat(db.sql("SELECT headers::text FROM http_idempotency WHERE key='legacy'")
			.query(String.class).single()).contains("Content-Type");
	}

	@Test
	void replayKeepsHistoricalCorrelationContentTypeAndBody() {
		String key = UUID.randomUUID().toString();
		IdempotencyExecutor.Reply first;
		try (var scope = new MessageContext("original", "cause", MessageContext.newTrace()).open()) {
			first = executor.write("headers", key, Map.of(), () -> executor.success(201, Map.of("id", key)));
		}
		try (var scope = new MessageContext("replay", "cause", MessageContext.newTrace()).open()) {
			var replay = executor.write("headers", key, Map.of(), () -> { throw new AssertionError("reexecuted"); });
			assertThat(replay).isEqualTo(first);
			assertThat(HttpResponseMapper.toResponse(replay).getHeaders().getFirst("X-Correlation-Id"))
				.isEqualTo("original");
		}
	}

	@Test
	void oneHundredConcurrentRequestsCommitOneEffect() throws Exception {
		String key = UUID.randomUUID().toString();
		var start = new CountDownLatch(1);
		try (var pool = Executors.newFixedThreadPool(16)) {
			var tasks = new java.util.ArrayList<java.util.concurrent.Future<IdempotencyExecutor.Reply>>();
			for (int i = 0; i < 100; i++) {
				tasks.add(pool.submit(() -> {
					start.await();
					return executor.write("race", key, Map.of("quantity", 1), () -> {
						db.sql("INSERT INTO message_outbox(event_id,event_type,body) VALUES (:id,'Test','{}')")
							.param("id", UUID.fromString(key)).update();
						return executor.success(201, Map.of("id", key));
					});
				}));
			}
			start.countDown();
			var expected = tasks.getFirst().get(30, TimeUnit.SECONDS);
			for (var task : tasks) {
				assertThat(task.get(30, TimeUnit.SECONDS)).isEqualTo(expected);
			}
		}
		assertThat(db.sql("SELECT count(*) FROM message_outbox WHERE event_id=:id")
			.param("id", UUID.fromString(key)).query(Long.class).single()).isEqualTo(1);
	}

	@Test
	void canonicalHashAndOperationScopeProtectOriginalResponse() {
		String key = UUID.randomUUID().toString();
		var payload = new LinkedHashMap<String, Object>();
		payload.put("z", 1);
		payload.put("a", Map.of("b", 2));
		var original = executor.write("first", key, payload, () -> executor.success(200, "original"));
		assertThat(executor.write("first", key, Map.of("a", Map.of("b", 2), "z", 1),
			() -> { throw new AssertionError("not canonical"); })).isEqualTo(original);
		assertThat(executor.write("first", key, Map.of("z", 2), () -> executor.success(200, "wrong")).status())
			.isEqualTo(409);
		assertThat(executor.write("second", key, payload, () -> executor.success(201, "separate")).status())
			.isEqualTo(201);
	}

	@Test
	void definitiveFailuresAreHistoricalButTechnicalFailuresRollBack() {
		for (int code : new int[] { 404, 409 }) {
			String key = UUID.randomUUID().toString();
			var original = executor.write("business", key, Map.of(), () -> executor.problem(code, "BUSINESS"));
			assertThat(executor.write("business", key, Map.of(), () -> executor.success(200, "changed")))
				.isEqualTo(original);
		}
		String key = UUID.randomUUID().toString();
		assertThatThrownBy(() -> executor.write("technical", key, Map.of(), () -> {
			throw new IllegalStateException("connection lost");
		})).isInstanceOf(IllegalStateException.class);
		assertThat(executor.write("technical", key, Map.of(), () -> executor.success(201, "recovered")).status())
			.isEqualTo(201);
	}

	@Test
	void lockTimeoutDoesNotPoisonIdentity() throws Exception {
		String key = UUID.randomUUID().toString();
		try (Connection held = source.getConnection()) {
			held.setAutoCommit(false);
			try (var statement = held.prepareStatement("INSERT INTO http_idempotency(operation,key,fingerprint) "
					+ "VALUES ('blocked',?,'hash')")) {
				statement.setString(1, key);
				statement.executeUpdate();
			}
			assertThatThrownBy(() -> executor.write("blocked", key, Map.of(), () -> executor.success(201, "ok")))
				.isInstanceOf(org.springframework.dao.DataAccessException.class);
			held.rollback();
		}
		assertThat(executor.write("blocked", key, Map.of(), () -> executor.success(201, "ok")).status())
			.isEqualTo(201);
	}

	@Test
	void deadlockAndSerializationRetryWholeTransactionAtMostThreeTimes() {
		for (String state : new String[] { "40P01", "40001" }) {
			String key = UUID.randomUUID().toString();
			var attempts = new java.util.concurrent.atomic.AtomicInteger();
			var reply = executor.write("retry", key, Map.of(), () -> {
				db.sql("INSERT INTO message_outbox(event_id,event_type,body) VALUES (:id,'Test','{}')")
					.param("id", UUID.fromString(key)).update();
				if (attempts.incrementAndGet() < 3) {
					throw new org.springframework.dao.CannotAcquireLockException("injected",
							new java.sql.SQLException("transaction aborted", state));
				}
				return executor.success(201, key);
			});
			assertThat(reply.status()).isEqualTo(201);
			assertThat(attempts.get()).isEqualTo(3);
		}
		var attempts = new java.util.concurrent.atomic.AtomicInteger();
		assertThatThrownBy(() -> executor.write("exhausted", UUID.randomUUID().toString(), Map.of(), () -> {
			attempts.incrementAndGet();
			throw new org.springframework.dao.CannotAcquireLockException("injected",
					new java.sql.SQLException("transaction aborted", "40001"));
		})).isInstanceOf(org.springframework.dao.DataAccessException.class);
		assertThat(attempts.get()).isEqualTo(3);
	}
}
