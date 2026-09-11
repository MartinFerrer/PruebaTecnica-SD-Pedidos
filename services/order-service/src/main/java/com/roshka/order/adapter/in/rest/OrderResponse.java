package com.roshka.order.adapter.in.rest;

import java.util.List;
import java.util.UUID;

public record OrderResponse(UUID orderId, String status, List<Line> items, List<Shortage> unavailableItems,
		String inventoryCancellationStatus, String statusUrl) {
	public record Line(UUID productId, long quantity) {
	}

	public record Shortage(UUID productId, long requested, long available, String reason) {
	}
}
