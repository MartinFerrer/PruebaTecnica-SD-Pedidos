package com.roshka.inventory.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import net.jqwik.api.Assume;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.LongRange;

class StockPropertiesTest {

	@Property(tries = 80, seed = "20260911")
	void availableIsNeverNegative(@ForAll @LongRange(min = 0, max = 1_000_000) long onHand,
			@ForAll @LongRange(min = 0, max = 1_000_000) long reserved) {
		Assume.that(reserved <= onHand);

		assertThat(new Stock(onHand, reserved, 1).available()).isGreaterThanOrEqualTo(0);
	}

	@Property(tries = 80, seed = "20260911")
	void reserveAndReleasePreservePhysicalStock(
			@ForAll @LongRange(min = 1, max = 1_000_000) long onHand,
			@ForAll @LongRange(min = 0, max = 1_000_000) long reserved,
			@ForAll @LongRange(min = 1, max = 1_000_000) long quantity) {
		Assume.that(reserved <= onHand);
		Assume.that(quantity <= onHand - reserved);

		Stock initial = new Stock(onHand, reserved, 1);
		Stock afterReservation = initial.reserve(quantity);
		Stock afterRelease = afterReservation.release(quantity);

		assertThat(afterReservation.onHand()).isEqualTo(onHand);
		assertThat(afterRelease).isEqualTo(new Stock(onHand, reserved, 3));
	}

	@Property(tries = 60, seed = "20260911")
	void staleRecountNeverOverwritesTheCurrentVersion(
			@ForAll @LongRange(min = 0, max = 1_000_000) long onHand,
			@ForAll @LongRange(min = 0, max = 1_000_000) long reserved,
			@ForAll @LongRange(min = 0, max = 1_000_000) long recount) {
		Assume.that(reserved <= onHand);
		Assume.that(recount >= reserved);

		Stock current = new Stock(onHand, reserved, 2);

		assertThatThrownBy(() -> current.recount(recount, 1))
				.isInstanceOfSatisfying(BusinessException.class,
						failure -> assertThat(failure.code()).isEqualTo("STOCK_VERSION_CONFLICT"));
	}

}
