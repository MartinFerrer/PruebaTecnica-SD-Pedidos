package com.roshka.order.application.port.out;

import com.roshka.order.domain.Order;
import java.util.*;

public interface OrderStore {
  Optional<Order> find(UUID id, boolean lock);

  List<Order> findAll();

  void save(Order order);
}
