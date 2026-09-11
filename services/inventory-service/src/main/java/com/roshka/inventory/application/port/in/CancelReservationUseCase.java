package com.roshka.inventory.application.port.in;

import java.util.UUID;

public interface CancelReservationUseCase {
  void cancel(UUID orderId, long orderVersion);
}
