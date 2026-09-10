package com.roshka.inventory.application.port.in;

import com.roshka.inventory.domain.Reservation.Item;
import java.util.*;

public interface Reservations {
  void reserve(UUID orderId, long version, List<Item> items);

  void cancel(UUID orderId, long version);
}
