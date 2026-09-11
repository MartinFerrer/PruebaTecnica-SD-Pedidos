package com.roshka.platform.messaging;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

class ConfirmedPublisherTest {

	@Test
	void returnAndNackNeverCountAsConfirmedPublication() {
		for (boolean returned : new boolean[] { true, false }) {
			var rabbit = mock(RabbitTemplate.class);
			doAnswer(call -> {
				CorrelationData correlation = call.getArgument(3);
				if (returned) {
					correlation.setReturned(new ReturnedMessage(
							call.getArgument(2), 312, "NO_ROUTE", "events", "missing"));
				}
				correlation.getFuture().complete(new CorrelationData.Confirm(returned, "nack"));
				return null;
			}).when(rabbit).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
			assertThatThrownBy(() -> new ConfirmedPublisher(rabbit).send("events", "missing", new Message(new byte[0])))
				.isInstanceOf(IllegalStateException.class).hasMessage("PUBLISH_NOT_CONFIRMED_OR_RETURNED");
		}
	}

	@Test
	void uncertainConfirmTimesOutAndOversizedMessageIsRejected() {
		var publisher = new ConfirmedPublisher(mock(RabbitTemplate.class));
		assertThatThrownBy(() -> publisher.send("events", "route", new Message(new byte[0])))
			.isInstanceOf(IllegalStateException.class).hasMessage("PUBLISH_UNCERTAIN");
		assertThatThrownBy(() -> publisher.send("events", "route", new Message(new byte[262145])))
			.isInstanceOf(IllegalArgumentException.class).hasMessage("MESSAGE_TOO_LARGE");
	}
}
