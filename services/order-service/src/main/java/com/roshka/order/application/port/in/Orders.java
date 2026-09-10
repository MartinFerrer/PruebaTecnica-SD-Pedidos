package com.roshka.order.application.port.in;

import com.roshka.order.domain.Order;
import java.util.*;

public interface Orders {
  record Cancellation(Order order, boolean accepted) {}

  Order create(List<Order.Item> items);

  Order get(UUID id);

  List<Order> list();

  Cancellation cancel(UUID id, String reason);

  void result(UUID id, long requestOrderVersion, boolean reserved, List<Order.Shortage> shortages);

  void released(UUID id, long requestOrderVersion);
}
