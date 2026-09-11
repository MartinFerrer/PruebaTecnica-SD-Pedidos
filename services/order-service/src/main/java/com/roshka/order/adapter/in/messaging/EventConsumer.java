package com.roshka.order.adapter.in.messaging;

import com.rabbitmq.client.Channel;
import com.roshka.order.application.port.in.ApplyReservationResultUseCase;
import com.roshka.order.application.port.in.CompleteInventoryCancellationUseCase;
import com.roshka.order.domain.BusinessException;
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

	private final ApplyReservationResultUseCase reservationResults;

	private final CompleteInventoryCancellationUseCase cancellationResults;

	public EventConsumer(JsonCodec json, JdbcClient db, PlatformTransactionManager manager,
			ConfirmedPublisher publisher, ApplyReservationResultUseCase reservationResults,
			CompleteInventoryCancellationUseCase cancellationResults, PlatformMetrics metrics) {
		super(json, db, manager, publisher, "order", "order.in", "order.retry.", "order.dlq", "inventory-service",
				metrics);
		this.reservationResults = reservationResults;
		this.cancellationResults = cancellationResults;
	}

	@RabbitListener(queues = "order.in")
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
		long version = payload.path("requestOrderVersion").asLong();
		switch (type) {
			case "StockReserved" ->
				reservationResults.apply(new ApplyReservationResultUseCase.Reserved(id, version, List.of(json().mapper
					.treeToValue(required(payload, "items"), ApplyReservationResultUseCase.Item[].class))));
			case "StockRejected" -> reservationResults.apply(new ApplyReservationResultUseCase.Rejected(id, version,
					List.of(json().mapper.treeToValue(required(payload, "unavailableItems"),
							ApplyReservationResultUseCase.Shortage[].class))));
			case "StockReleased" -> cancellationResults
				.complete(new CompleteInventoryCancellationUseCase.Result(id, version, requiredText(payload, "outcome"),
						List.of(json().mapper.treeToValue(required(payload, "releasedItems"),
								CompleteInventoryCancellationUseCase.Item[].class))));
			default -> throw new IllegalArgumentException("UNSUPPORTED_EVENT");
		}
	}

}
