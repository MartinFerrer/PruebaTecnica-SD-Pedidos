package com.roshka.order.application.port.in;

import com.roshka.order.domain.Order;
import java.util.UUID;

public interface CancelOrderUseCase {
  record Result(Order order, boolean accepted) {}

  Result cancel(UUID orderId, String reason);
}
