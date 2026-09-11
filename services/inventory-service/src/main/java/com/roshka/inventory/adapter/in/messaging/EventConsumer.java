package com.roshka.inventory.adapter.in.messaging;

import com.rabbitmq.client.Channel;
import com.roshka.inventory.application.port.in.CancelReservationUseCase;
import com.roshka.inventory.application.port.in.ReserveStockUseCase;
import com.roshka.inventory.domain.BusinessException;
import com.roshka.platform.json.JsonCodec;
import com.roshka.platform.messaging.ConfirmedPublisher;
import com.roshka.platform.messaging.TransactionalEventConsumer;
import com.roshka.platform.observability.PlatformMetrics;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.JsonNode;

@Component
public class EventConsumer extends TransactionalEventConsumer {

	private final ReserveStockUseCase reserveStock;

	private final CancelReservationUseCase cancelReservation;

	public EventConsumer(JsonCodec json, JdbcClient db, PlatformTransactionManager manager,
			ConfirmedPublisher publisher, ReserveStockUseCase reserveStock,
			CancelReservationUseCase cancelReservation, PlatformMetrics metrics) {
		super(json, db, manager, publisher, "inventory", "inventory.in", "inventory.retry.", "inventory.dlq",
				"order-service", metrics);
		this.reserveStock = reserveStock;
		this.cancelReservation = cancelReservation;
	}

	@RabbitListener(queues = "inventory.in")
	public void receive(Message message, Channel channel) throws IOException {
		consume(message, channel);
	}

	@Override
	protected boolean isPermanentFailure(RuntimeException failure) {
		return super.isPermanentFailure(failure) || failure instanceof BusinessException;
	}

	@Override
	protected void handle(JsonNode event) {
		UUID id = UUID.fromString(requiredText(event, "aggregateId"));
		JsonNode payload = event.get("payload");
		String type = requiredText(event, "eventType");
		long version = event.get("aggregateVersion").asLong();
		switch (type) {
			case "OrderCreated" -> reserveStock.reserve(id, version, List.of(json().mapper
				.treeToValue(required(payload, "items"), com.roshka.inventory.domain.Reservation.Item[].class)));
			case "OrderCancelled" -> cancelReservation.cancel(id, version);
			default -> throw new IllegalArgumentException("UNSUPPORTED_EVENT");
		}
	}

}
