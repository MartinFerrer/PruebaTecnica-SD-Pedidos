package com.roshka.order.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.LongRange;

class OrderPropertiesTest {

	@Property(tries = 80, seed = "20260911")
	void cancellationIsTerminalForEveryValidQuantity(
			@ForAll @LongRange(min = 1, max = 1_000_000_000) long quantity) {
		UUID productId = UUID.randomUUID();
		Order pending = pending(productId, quantity);

		Order cancelled = pending.cancel();
		Order completed = cancelled.released(cancelled.cancellationVersion());

		assertThat(cancelled.cancel()).isSameAs(cancelled);
		assertThat(cancelled.result(true, List.of())).isSameAs(cancelled);
		assertThat(completed.released(cancelled.cancellationVersion())).isSameAs(completed);
	}

	@Property(tries = 80, seed = "20260911")
	void aReservationResultIsAppliedOnlyOnce(
			@ForAll @LongRange(min = 1, max = 1_000_000_000) long quantity,
			@ForAll boolean accepted) {
		Order pending = pending(UUID.randomUUID(), quantity);
		Order result = pending.result(accepted, List.of());

		assertThat(result.result(!accepted, List.of())).isSameAs(result);
	}

	@Property(tries = 60, seed = "20260911")
	void rejectedOrdersCannotEnterTheCancellationFlow(
			@ForAll @LongRange(min = 1, max = 1_000_000_000) long quantity) {
		Order rejected = pending(UUID.randomUUID(), quantity).result(false, List.of());

		assertThatThrownBy(rejected::cancel).isInstanceOfSatisfying(BusinessException.class,
				failure -> assertThat(failure.code()).isEqualTo("ORDER_REJECTED"));
	}

	private Order pending(UUID productId, long quantity) {
		return new Order(UUID.randomUUID(), List.of(new Order.Item(productId, quantity)), OrderStatus.PENDING,
				1, null, 0, List.of());
	}

}
