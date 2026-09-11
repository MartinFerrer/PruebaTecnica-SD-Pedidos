package com.roshka.order.domain;

public enum OrderStatus {

	PENDING, CONFIRMED, REJECTED, CANCELLED;

	public OrderStatus cancel() {
		if (this == REJECTED) {
			throw BusinessException.conflict("ORDER_REJECTED");
		}
		return CANCELLED;
	}

	public OrderStatus reservationResult(boolean accepted) {
		if (this != PENDING) {
			return this;
		}
		return accepted ? CONFIRMED : REJECTED;
	}

}
