package com.roshka.platform.messaging;

import com.roshka.platform.json.JsonCodec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Set;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

/** Operator entrypoint; broker credentials are read only from the environment. */
public final class DlqReplayCommand {

	private DlqReplayCommand() {
	}

	public static void main(String[] args) throws Exception {
		if (args.length != 4 || !Set.of("CONFIRMED", "REJECTED", "CANCELLED").contains(args[3])) {
			throw new IllegalArgumentException(
					"Usage: <order|inventory> <eventId> <order-status-url> <terminal-status>");
		}
		URI statusUrl = URI.create(args[2]);
		if (!Set.of("http", "https").contains(statusUrl.getScheme()) || statusUrl.getUserInfo() != null
				|| !statusUrl.getPath().matches("/orders/[0-9a-fA-F-]{36}")) {
			throw new IllegalArgumentException("Expected public GET /orders/{orderId} URL without credentials");
		}
		var factory = new CachingConnectionFactory(env("RABBITMQ_HOST", "localhost"),
				Integer.parseInt(env("RABBITMQ_PORT", "5672")));
		factory.setUsername(env("RABBITMQ_USER", "guest"));
		factory.setPassword(env("RABBITMQ_PASSWORD", "guest"));
		factory.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
		factory.setPublisherReturns(true);
		try {
			var template = new RabbitTemplate(factory);
			template.setMandatory(true);
			try (var channel = factory.createConnection().createChannel(false)) {
				new DlqReplay(new ConfirmedPublisher(template)).replayOne(channel, args[0], args[1],
						event -> statusUrl.getPath().equals("/orders/" + event.path("aggregateId").asString())
								&& awaitConvergence(statusUrl, args[3]));
			}
			System.out.println("Replay confirmed, terminal state verified, original acknowledged: " + args[1]);
		}
		finally {
			factory.destroy();
		}
	}

	private static boolean awaitConvergence(URI url, String expected) {
		try (var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
			long deadline = System.nanoTime() + Duration.ofSeconds(45).toNanos();
			while (System.nanoTime() < deadline) {
				var response = http.send(HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(5)).GET().build(),
						HttpResponse.BodyHandlers.ofString());
				if (response.statusCode() == 200) {
					var body = new JsonCodec().mapper.readTree(response.body());
					if (url.getPath().equals("/orders/" + body.path("orderId").asString())
							&& expected.equals(body.path("status").asString())
							&& (!expected.equals("CANCELLED")
									|| "COMPLETED".equals(body.path("inventoryCancellationStatus").asString()))) {
						return true;
					}
				}
				Thread.sleep(250);
			}
		}
		catch (InterruptedException failure) {
			Thread.currentThread().interrupt();
		}
		catch (Exception failure) {
			System.err.println("Cannot verify convergence: " + failure.getClass().getSimpleName());
		}
		return false;
	}

	private static String env(String key, String fallback) {
		return System.getenv().getOrDefault(key, fallback);
	}
}
