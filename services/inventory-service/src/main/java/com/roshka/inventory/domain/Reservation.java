package com.roshka.inventory.domain;

import java.util.List;
import java.util.Set;
import java.util.UUID;

public record Reservation(UUID orderId, String state, long lastOrderVersion, long version, List<Item> items) {
	public record Item(UUID productId, long quantity) {
		public Item {
			if (productId == null || quantity < 1 || quantity > Stock.MAX_QUANTITY) {
				throw new IllegalArgumentException("INVALID_ITEM");
			}
		}
	}

	public Reservation {
		items = List.copyOf(items);
		if (orderId == null
				|| !Set.of("RESERVED", "REJECTED", "RELEASED", "CANCELLED_BEFORE_RESERVATION").contains(state)
				|| lastOrderVersion < 1 || version < 1
				|| items.stream().map(Item::productId).distinct().count() != items.size()
				|| ("CANCELLED_BEFORE_RESERVATION".equals(state) && !items.isEmpty())
				|| (!"CANCELLED_BEFORE_RESERVATION".equals(state) && items.isEmpty())) {
			throw new IllegalArgumentException("INVALID_RESERVATION");
		}
	}
}
