package com.roshka.order.application.service;

import com.roshka.order.application.port.in.Orders;
import com.roshka.order.application.port.out.*;
import com.roshka.order.domain.*;
import java.util.*;
import java.util.function.Supplier;

public class OrderService implements Orders {
  private final OrderStore store;
  private final Events events;
  private final Supplier<UUID> ids;

  public OrderService(OrderStore store, Events events, Supplier<UUID> ids) {
    this.store = store;
    this.events = events;
    this.ids = ids;
  }

  public Order create(List<Order.Item> items) {
    Order order = new Order(ids.get(), items, OrderStatus.PENDING, 1, null, 0, List.of());
    store.save(order);
    events.append(
        "OrderCreated",
        order.orderId(),
        1,
        Map.of("orderId", order.orderId(), "items", order.items()));
    return order;
  }

  public Order get(UUID id) {
    return find(id, false);
  }

  public List<Order> list() {
    return store.findAll();
  }

  public Cancellation cancel(UUID id, String reason) {
    Order before = find(id, true), after = before.cancel();
    if (after != before) {
      store.save(after);
      events.append("OrderCancelled", id, after.version(), Map.of("orderId", id, "reason", reason));
    }
    return new Cancellation(after, after != before);
  }

  public void result(
      UUID id, long requestOrderVersion, boolean reserved, List<Order.Shortage> shortages) {
    if (requestOrderVersion != 1)
      throw new BusinessException(BusinessException.Kind.CONFLICT, "UNEXPECTED_RESERVATION_RESULT");
    Order before = find(id, true), after = before.result(reserved, shortages);
    if (after != before) store.save(after);
  }

  public void released(UUID id, long requestOrderVersion) {
    Order before = find(id, true), after = before.released(requestOrderVersion);
    if (after != before) store.save(after);
  }

  private Order find(UUID id, boolean lock) {
    return store.find(id, lock).orElseThrow(
            () -> new BusinessException(BusinessException.Kind.NOT_FOUND, "ORDER_NOT_FOUND"));
  }
}
