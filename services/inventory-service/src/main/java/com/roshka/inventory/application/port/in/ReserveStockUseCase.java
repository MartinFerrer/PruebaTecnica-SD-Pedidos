package com.roshka.inventory.application.port.in;

import com.roshka.inventory.domain.Reservation;
import java.util.List;
import java.util.UUID;

public interface ReserveStockUseCase {
  void reserve(UUID orderId, long orderVersion, List<Reservation.Item> items);
}
