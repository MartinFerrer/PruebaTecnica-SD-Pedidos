package com.roshka.order.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class OrderStatusTest {

	@Test
	void pendingCanConfirmOrReject() {
		assertThat(OrderStatus.PENDING.reservationResult(true)).isEqualTo(OrderStatus.CONFIRMED);
		assertThat(OrderStatus.PENDING.reservationResult(false)).isEqualTo(OrderStatus.REJECTED);
	}

	@Test
	void cancellationIsTerminalEvenWithLateResults() {
		for (OrderStatus source : new OrderStatus[] { OrderStatus.PENDING, OrderStatus.CONFIRMED,
				OrderStatus.CANCELLED }) {
			assertThat(source.cancel()).isEqualTo(OrderStatus.CANCELLED);
		}
		assertThat(OrderStatus.CANCELLED.reservationResult(true)).isEqualTo(OrderStatus.CANCELLED);
		assertThat(OrderStatus.CANCELLED.reservationResult(false)).isEqualTo(OrderStatus.CANCELLED);
	}

	@Test
	void cannotCancelRejectedAndResultsDoNotChangeTerminalState() {
		assertThatThrownBy(() -> OrderStatus.REJECTED.cancel()).isInstanceOfSatisfying(BusinessException.class,
				failure -> {
					assertThat(failure.kind()).isEqualTo(BusinessException.Kind.CONFLICT);
					assertThat(failure.code()).isEqualTo("ORDER_REJECTED");
				});
		assertThat(OrderStatus.CONFIRMED.reservationResult(true)).isEqualTo(OrderStatus.CONFIRMED);
		assertThat(OrderStatus.REJECTED.reservationResult(false)).isEqualTo(OrderStatus.REJECTED);
	}

}
