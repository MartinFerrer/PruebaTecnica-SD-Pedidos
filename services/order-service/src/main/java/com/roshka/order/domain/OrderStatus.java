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
		return this == PENDING ? (accepted ? CONFIRMED : REJECTED) : this;
	}

}
