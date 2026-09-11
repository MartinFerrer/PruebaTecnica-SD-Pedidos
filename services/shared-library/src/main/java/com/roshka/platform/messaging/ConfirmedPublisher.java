package com.roshka.platform.messaging;

import com.roshka.platform.observability.PlatformMetrics;
import java.util.concurrent.TimeUnit;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class ConfirmedPublisher {

	private final RabbitTemplate rabbit;

	private final PlatformMetrics metrics;

	public ConfirmedPublisher(RabbitTemplate rabbit) {
		this(rabbit, PlatformMetrics.noop());
	}

	@Autowired
	public ConfirmedPublisher(RabbitTemplate rabbit, PlatformMetrics metrics) {
		this.rabbit = rabbit;
		this.metrics = metrics;
	}

	public void send(String exchange, String routingKey, Message message) {
		if (message.getBody().length > 262_144) {
			throw new IllegalArgumentException("MESSAGE_TOO_LARGE");
		}
		CorrelationData correlation = new CorrelationData();
		rabbit.send(exchange, routingKey, message, correlation);
		try {
			var confirm = correlation.getFuture().get(5, TimeUnit.SECONDS);
			if (!confirm.ack() || correlation.getReturned() != null) {
				metrics.increment("publisher_confirms_total", "outcome", "negative");
				throw new IllegalStateException("PUBLISH_NOT_CONFIRMED_OR_RETURNED");
			}
			metrics.increment("publisher_confirms_total", "outcome", "acknowledged");
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			metrics.increment("publisher_confirms_total", "outcome", "interrupted");
			throw new IllegalStateException("PUBLISH_INTERRUPTED", e);
		}
		catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
			metrics.increment("publisher_confirms_total", "outcome", "uncertain");
			throw new IllegalStateException("PUBLISH_UNCERTAIN", e);
		}
	}

}
