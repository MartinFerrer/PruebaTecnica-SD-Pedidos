package com.roshka.platform.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rabbitmq.client.Channel;
import com.roshka.platform.json.JsonCodec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.support.DefaultMessagePropertiesConverter;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import tools.jackson.databind.JsonNode;

class MessagingIT {

	static final PostgreSQLContainer DB = new PostgreSQLContainer("postgres:18.3-alpine");

	static final RabbitMQContainer BROKER = new RabbitMQContainer("rabbitmq:4.2.3-management-alpine");

	static final JsonCodec JSON = new JsonCodec();

	static JdbcClient db;

	static JdbcTransactionManager manager;

	static CachingConnectionFactory connection;

	static RabbitTemplate rabbit;

	static ConfirmedPublisher publisher;

	@BeforeAll
	static void start() {
		DB.start();
		BROKER.start();
		var source = new DriverManagerDataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword());
		db = JdbcClient.create(source);
		manager = new JdbcTransactionManager(source);
		Flyway.configure().dataSource(source).locations("filesystem:"
				+ Path.of("../inventory-service/src/main/resources/db/migration").toAbsolutePath()).load().migrate();
		connection = new CachingConnectionFactory(BROKER.getHost(), BROKER.getAmqpPort());
		connection.setUsername(BROKER.getAdminUsername());
		connection.setPassword(BROKER.getAdminPassword());
		connection.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
		connection.setPublisherReturns(true);
		rabbit = new RabbitTemplate(connection);
		rabbit.setMandatory(true);
		publisher = new ConfirmedPublisher(rabbit);
		var admin = new RabbitAdmin(connection);
		for (var declaration : new BrokerTopology().topology().getDeclarables()) {
			if (declaration instanceof org.springframework.amqp.core.Queue queue) {
				admin.declareQueue(queue);
			}
			else if (declaration instanceof org.springframework.amqp.core.Exchange exchange) {
				admin.declareExchange(exchange);
			}
			else if (declaration instanceof org.springframework.amqp.core.Binding binding) {
				admin.declareBinding(binding);
			}
		}
	}

	@BeforeEach
	void clear() {
		db.sql("TRUNCATE message_outbox,message_inbox").update();
		var admin = new RabbitAdmin(connection);
		for (String queue : java.util.List.of("inventory.in", "inventory.dlq", "inventory.retry.1",
				"inventory.retry.5", "inventory.retry.30")) {
			admin.purgeQueue(queue, false);
		}
	}

	@AfterAll
	static void stop() {
		if (connection != null) {
			connection.destroy();
		}
		BROKER.stop();
		DB.stop();
	}

	@Test
	void outboxPreservesContextInAmqpHeaders() {
		var event = EventContractTest.event("OrderCreated");
		insert(event);
		new OutboxRelay(db, publisher, manager).dispatch();
		var message = rabbit.receive("inventory.in", 5000);
		assertThat(message).isNotNull();
		for (String field : java.util.List.of("traceparent", "correlationId", "causationId")) {
			assertThat(message.getMessageProperties().getHeaders().get(field)).isEqualTo(event.path(field).asString());
		}
		assertThat(message.getMessageProperties().getMessageId()).isEqualTo(event.path("eventId").asString());
		assertThat(new String(message.getBody(), StandardCharsets.UTF_8)).isEqualTo(JSON.write(event));
	}

	@Test
	void processLossAroundBusinessCommitKeepsOutboxAtomic() {
		var tx = new org.springframework.transaction.support.TransactionTemplate(manager);
		assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
			insert(EventContractTest.event("OrderCreated"));
			throw new Crash();
		})).isInstanceOf(Crash.class);
		assertThat(db.sql("SELECT count(*) FROM message_outbox").query(Long.class).single()).isZero();
		tx.executeWithoutResult(status -> insert(EventContractTest.event("OrderCreated")));
		// A new relay represents restart after the business commit, before any publication.
		new OutboxRelay(db, publisher, manager).dispatch();
		assertThat(rabbit.receive("inventory.in", 5000)).isNotNull();
	}

	@Test
	void impossibleInvariantIsPermanentAndDoesNotCommitInbox() throws Exception {
		var event = EventContractTest.event("OrderCreated");
		publisher.send("", "inventory.in", new Message(JSON.write(event).getBytes(StandardCharsets.UTF_8)));
		try (Channel channel = connection.createConnection().createChannel(false)) {
			new Consumer(publisher, true).accept(take(channel), channel);
		}
		assertThat(rabbit.receive("inventory.dlq", 3000)).isNotNull();
		assertThat(db.sql("SELECT count(*) FROM message_inbox").query(Long.class).single()).isZero();
	}

	static void insert(JsonNode event) {
		db.sql("INSERT INTO message_outbox(event_id,event_type,body) VALUES (:id,:type,:body)")
			.param("id", UUID.fromString(event.path("eventId").asString()))
			.param("type", event.path("eventType").asString()).param("body", JSON.write(event)).update();
	}

	@Test
	void crashBeforeAndAfterOutboxConfirmRecoversWithStableIdentity() {
		for (String point : java.util.List.of("before-publish", "after-confirm", "before-outbox-update",
				"after-outbox-update")) {
			clear();
			var event = EventContractTest.event("OrderCreated");
			insert(event);
			var crashing = new OutboxRelay(db, publisher, manager) {
				@Override
				protected void checkpoint(String stage) {
					if (point.equals(stage)) {
						throw new Crash();
					}
				}
			};
			assertThatThrownBy(crashing::dispatch).isInstanceOf(Crash.class);
			boolean committed = point.equals("after-outbox-update");
			assertThat(db.sql("SELECT published FROM message_outbox").query(Boolean.class).single())
				.isEqualTo(committed);
			db.sql("UPDATE message_outbox SET lease_until=now()-interval '1 second'").update();
			new OutboxRelay(db, publisher, manager).dispatch();
			var messages = new java.util.ArrayList<Message>();
			Message message;
			while ((message = rabbit.receive("inventory.in", 500)) != null) {
				messages.add(message);
			}
			assertThat(messages).hasSize(point.equals("before-publish") || committed ? 1 : 2);
			assertThat(messages).allSatisfy(copy -> assertThat(copy.getMessageProperties().getMessageId())
				.isEqualTo(event.path("eventId").asString()));
		}
	}

	@Test
	void crashAroundConsumerCommitAndAckNeverRepeatsEffect() throws Exception {
		for (String point : java.util.List.of("before-commit", "after-commit", "before-ack", "after-ack")) {
			clear();
			var event = EventContractTest.event("OrderCreated");
			publisher.send("", "inventory.in", new Message(JSON.write(event).getBytes(StandardCharsets.UTF_8)));
			try (Channel channel = connection.createConnection().createChannel(false)) {
				var consumer = new Consumer(publisher, false) {
					@Override
					protected void checkpoint(String stage) {
						if (point.equals(stage)) {
							throw new Crash();
						}
					}
				};
				assertThatThrownBy(() -> consumer.accept(take(channel), channel)).isInstanceOf(Crash.class);
				channel.abort();
			}
			assertThat(db.sql("SELECT count(*) FROM message_inbox").query(Long.class).single())
				.isEqualTo(point.equals("before-commit") ? 0 : 1);
			if (!point.equals("after-ack")) {
				try (Channel channel = connection.createConnection().createChannel(false)) {
					var redelivery = take(channel);
					assertThat(redelivery.getMessageProperties().isRedelivered()).isTrue();
					new Consumer(publisher, false).accept(redelivery, channel);
				}
			}
			assertThat(db.sql("SELECT count(*) FROM message_inbox").query(Long.class).single()).isEqualTo(1);
			assertThat(db.sql("SELECT count(*) FROM message_outbox").query(Long.class).single()).isEqualTo(1);
		}
	}

	@Test
	void fullRetrySequencePreservesContextAndEndsInDlq() throws Exception {
		var event = EventContractTest.event("OrderCreated");
		insert(event);
		new OutboxRelay(db, publisher, manager).dispatch();
		for (int attempt = 0; attempt < 4; attempt++) {
			try (Channel channel = connection.createConnection().createChannel(false)) {
				final int expected = attempt;
				var delivery = org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(40)).until(
						() -> channel.basicGet("inventory.in", false), java.util.Objects::nonNull);
				var props = new DefaultMessagePropertiesConverter().toMessageProperties(
						delivery.getProps(), delivery.getEnvelope(), "UTF-8");
				assertThat(((Number) props.getHeaders().getOrDefault("retry-attempt", 0)).intValue())
					.isEqualTo(expected);
				new Consumer(publisher, false) {
					@Override
					protected void handle(JsonNode ignored) {
						throw new org.springframework.dao.CannotAcquireLockException("transient");
					}
				}.accept(new Message(delivery.getBody(), props), channel);
			}
		}
		var failed = rabbit.receive("inventory.dlq", 5000);
		assertThat(failed).isNotNull();
		assertThat(failed.getMessageProperties().getHeaders()).containsEntry("retry-attempt", 4)
			.containsEntry("original-queue", "inventory.in")
			.containsEntry("traceparent", event.path("traceparent").asString());
		assertThat(new String(failed.getBody(), StandardCharsets.UTF_8)).isEqualTo(JSON.write(event));
		assertThat(db.sql("SELECT count(*) FROM message_inbox").query(Long.class).single()).isZero();
	}

	@Test
	void failedTransferLeavesOriginalUnacknowledged() throws Exception {
		var body = JSON.write(EventContractTest.event("OrderCreated")).getBytes(StandardCharsets.UTF_8);
		publisher.send("", "inventory.in", new Message(body));
		var unavailable = new ConfirmedPublisher(rabbit) {
			@Override
			public void send(String exchange, String route, Message message) {
				throw new IllegalStateException("confirm timeout");
			}
		};
		try (Channel channel = connection.createConnection().createChannel(false)) {
			assertThatThrownBy(() -> new Consumer(unavailable, true).accept(take(channel), channel))
				.isInstanceOf(IllegalStateException.class);
		}
		try (Channel channel = connection.createConnection().createChannel(false)) {
			var original = take(channel);
			assertThat(original.getBody()).isEqualTo(body);
			assertThat(original.getMessageProperties().isRedelivered()).isTrue();
			channel.basicAck(original.getMessageProperties().getDeliveryTag(), false);
		}
	}

	static final class Crash extends Error {
	}

	@Test
	void transferCrashesRetainOriginalOrConfirmedCopy() throws Exception {
		for (boolean permanent : java.util.List.of(false, true)) {
			for (String point : java.util.List.of("before-transfer", "after-transfer", "before-ack", "after-ack")) {
				clear();
				byte[] body = JSON.write(EventContractTest.event("OrderCreated")).getBytes(StandardCharsets.UTF_8);
				publisher.send("", "inventory.in", new Message(body));
				try (Channel channel = connection.createConnection().createChannel(false)) {
					var crashing = new Consumer(publisher, permanent) {
						@Override
						protected void handle(JsonNode event) {
							if (permanent) {
								throw new IllegalArgumentException("invalid payload");
							}
							throw new org.springframework.dao.CannotAcquireLockException("transient");
						}

						@Override
						protected void checkpoint(String stage) {
							if (point.equals(stage)) {
								throw new Crash();
							}
						}
					};
					assertThatThrownBy(() -> crashing.accept(take(channel), channel)).isInstanceOf(Crash.class);
					channel.abort();
				}
				if (!point.equals("after-ack")) {
					try (Channel channel = connection.createConnection().createChannel(false)) {
						var original = take(channel);
						assertThat(original.getBody()).isEqualTo(body);
						assertThat(original.getMessageProperties().isRedelivered()).isTrue();
						channel.basicAck(original.getMessageProperties().getDeliveryTag(), false);
					}
				}
				if (!point.equals("before-transfer")) {
					var copy = rabbit.receive(permanent ? "inventory.dlq" : "inventory.in", 5000);
					assertThat(copy).isNotNull();
					assertThat(copy.getBody()).isEqualTo(body);
				}
				assertThat(db.sql("SELECT count(*) FROM message_inbox").query(Long.class).single()).isZero();
			}
		}
	}

	@Test
	void unsuccessfulPublishAndLostLeaseRemainRecoverable() {
		for (String failure : java.util.List.of("return", "nack", "confirm-timeout", "lost-lease")) {
			clear();
			insert(EventContractTest.event("OrderCreated"));
			var failing = new ConfirmedPublisher(rabbit) {
				@Override
				public void send(String exchange, String route, Message message) {
					if (failure.equals("lost-lease")) {
						db.sql("UPDATE message_outbox SET lease_token=:token")
							.param("token", UUID.randomUUID()).update();
						return;
					}
					throw new IllegalStateException(failure);
				}
			};
			new OutboxRelay(db, failing, manager).dispatch();
			assertThat(db.sql("SELECT published FROM message_outbox").query(Boolean.class).single()).isFalse();
			db.sql("UPDATE message_outbox SET next_attempt=now(),lease_until=now()-interval '1 second'").update();
			new OutboxRelay(db, publisher, manager).dispatch();
			assertThat(db.sql("SELECT published FROM message_outbox").query(Boolean.class).single()).isTrue();
			assertThat(rabbit.receive("inventory.in", 5000)).isNotNull();
		}
	}

	@Test
	void brokerHasEffectiveQuorumRetriesBindingsAndSafeDeadLettering() throws Exception {
		String credentials = java.util.Base64.getEncoder().encodeToString(
				(BROKER.getAdminUsername() + ":" + BROKER.getAdminPassword()).getBytes(StandardCharsets.UTF_8));
		try (var http = java.net.http.HttpClient.newHttpClient()) {
			for (String service : java.util.List.of("order", "inventory")) {
				for (String suffix : java.util.List.of("in", "dlq", "retry.1", "retry.5", "retry.30")) {
					var url = java.net.URI.create("http://" + BROKER.getHost() + ":" + BROKER.getHttpPort()
							+ "/api/queues/%2F/" + service + "." + suffix);
					var response = http.send(java.net.http.HttpRequest.newBuilder(url)
							.header("Authorization", "Basic " + credentials).GET().build(),
							java.net.http.HttpResponse.BodyHandlers.ofString());
					assertThat(response.statusCode()).isEqualTo(200);
					var queue = JSON.mapper.readTree(response.body());
					assertThat(queue.path("type").asString()).isEqualTo("quorum");
					assertThat(queue.path("durable").asBoolean()).isTrue();
					var args = queue.path("arguments");
					if (!suffix.equals("dlq")) {
						assertThat(args.path("x-overflow").asString()).isEqualTo("reject-publish");
						assertThat(args.path("x-delivery-limit").asInt()).isEqualTo(20);
						assertThat(args.path("x-dead-letter-strategy").asString()).isEqualTo("at-least-once");
						assertThat(args.path("x-dead-letter-exchange").asString()).isEmpty();
						assertThat(args.path("x-dead-letter-routing-key").asString())
							.isEqualTo(service + (suffix.equals("in") ? ".dlq" : ".in"));
					}
					if (suffix.startsWith("retry.")) {
						assertThat(args.path("x-message-ttl").asInt())
							.isEqualTo(Integer.parseInt(suffix.substring(6)) * 1000);
					}
				}
			}
		}
		for (String type : java.util.List.of("OrderCreated", "OrderCancelled", "StockReserved",
				"StockRejected", "StockReleased", "ProductStockCreated", "ProductStockUpdated",
				"ProductStockReplenished")) {
			publisher.send("business.events", type, new Message(type.getBytes(StandardCharsets.UTF_8)));
			String queue = type.startsWith("Order") ? "inventory.in"
					: type.startsWith("Stock") ? "order.in" : "inventory.products";
			assertThat(rabbit.receive(queue, 5000).getBody()).isEqualTo(type.getBytes(StandardCharsets.UTF_8));
		}
	}

	@Test
	void dlqReplayKeepsOriginalUntilConvergenceAndDuplicatesRemainSafe() throws Exception {
		clear();
		var event = EventContractTest.event("OrderCreated");
		String id = event.path("eventId").asString();
		byte[] body = JSON.write(event).getBytes(StandardCharsets.UTF_8);
		publisher.send("", "inventory.dlq", new Message(body));
		try (Channel channel = connection.createConnection().createChannel(false)) {
			assertThatThrownBy(() -> new DlqReplay(publisher).replayOne(channel, "inventory", id, eventCopy -> false))
				.isInstanceOf(IllegalStateException.class);
		}
		try (Channel channel = connection.createConnection().createChannel(false)) {
			new Consumer(publisher, false).accept(take(channel), channel);
		}
		try (Channel channel = connection.createConnection().createChannel(false)) {
			new DlqReplay(publisher).replayOne(channel, "inventory", id, eventCopy -> true);
			new Consumer(publisher, false).accept(take(channel), channel);
		}
		assertThat(rabbit.receive("inventory.dlq", 500)).isNull();
		assertThat(db.sql("SELECT count(*) FROM message_inbox").query(Long.class).single()).isEqualTo(1);
		assertThat(db.sql("SELECT count(*) FROM message_outbox").query(Long.class).single()).isEqualTo(1);
	}

	@Test
	void replayOfInvalidMessageDoesNotRemoveOriginal() throws Exception {
		publisher.send("", "inventory.dlq", new Message("{}".getBytes(StandardCharsets.UTF_8)));
		try (Channel channel = connection.createConnection().createChannel(false)) {
			assertThatThrownBy(() -> new DlqReplay(publisher)
				.replayOne(channel, "inventory", "invalid", eventCopy -> true))
				.isInstanceOf(IllegalArgumentException.class);
		}
		assertThat(rabbit.receive("inventory.dlq", 3000)).isNotNull();
	}

	static Message take(Channel channel) {
		var result = org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(8)).until(
				() -> channel.basicGet("inventory.in", false), java.util.Objects::nonNull);
		var props = new DefaultMessagePropertiesConverter().toMessageProperties(
				result.getProps(), result.getEnvelope(), StandardCharsets.UTF_8.name());
		return new Message(result.getBody(), props);
	}

	static class Consumer extends TransactionalEventConsumer {
		private final boolean impossible;

		Consumer(ConfirmedPublisher sender, boolean impossible) {
			super(JSON, db, manager, sender, "inventory", "inventory.in", "inventory.retry.",
					"inventory.dlq", "order-service");
			this.impossible = impossible;
		}

		void accept(Message message, Channel channel) throws Exception {
			consume(message, channel);
		}

		@Override
		protected void handle(JsonNode event) {
			if (impossible) {
				throw new IllegalStateException("IMPOSSIBLE_INVARIANT");
			}
			insert(EventContractTest.event("StockReserved"));
		}
	}
}
