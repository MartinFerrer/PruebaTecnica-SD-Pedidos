package com.roshka.order.application.port.in;

import com.roshka.order.domain.Order;
import java.util.List;
import java.util.UUID;

public interface FindOrdersQuery {
  Order findById(UUID orderId);

  List<Order> findAll();
}
