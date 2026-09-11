package com.roshka.inventory.domain;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReservationTest {

	private final Reservation.Item item = new Reservation.Item(UUID.randomUUID(), 1);

	@Test
	void validatesStateVersionsAndItemShape() {
		assertThatThrownBy(() -> new Reservation(null, "RESERVED", 1, 1, List.of(item)))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new Reservation(UUID.randomUUID(), "UNKNOWN", 1, 1, List.of(item)))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new Reservation(UUID.randomUUID(), "RESERVED", 0, 1, List.of(item)))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new Reservation(UUID.randomUUID(), "RESERVED", 1, 0, List.of(item)))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new Reservation(UUID.randomUUID(), "RESERVED", 1, 1, List.of()))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new Reservation(UUID.randomUUID(), "RESERVED", 1, 1, List.of(item, item)))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(
				() -> new Reservation(UUID.randomUUID(), "CANCELLED_BEFORE_RESERVATION", 2, 1, List.of(item)))
			.isInstanceOf(IllegalArgumentException.class);
	}

}
