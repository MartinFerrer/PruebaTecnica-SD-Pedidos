package com.roshka.inventory.application.port.out;

import com.roshka.inventory.domain.Reservation;
import java.util.*;

public interface ReservationStore {
  void lock(UUID orderId);

  Optional<Reservation> find(UUID orderId);

  void save(Reservation reservation);
}
