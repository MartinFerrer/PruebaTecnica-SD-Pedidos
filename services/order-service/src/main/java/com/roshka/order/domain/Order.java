package com.roshka.order.domain;

import java.util.*;

public record Order(
    UUID orderId,
    List<Item> items,
    OrderStatus status,
    long version,
    String inventoryCancellationStatus,
    long cancellationVersion,
    List<Shortage> unavailableItems) {
  public record Item(UUID productId, long quantity) {
    public Item {
      if (productId == null || quantity < 1 || quantity > 1_000_000_000L)
        throw new IllegalArgumentException("INVALID_ITEM");
    }
  }

  public record Shortage(UUID productId, long requested, long available, String reason) {}

  public Order {
    items = List.copyOf(items);
    unavailableItems = List.copyOf(unavailableItems);
    if (items.isEmpty()
        || items.size() > 100
        || items.stream().map(Item::productId).distinct().count() != items.size())
      throw new BusinessException(BusinessException.Kind.INVALID, "INVALID_ITEMS");
  }

  public Order cancel() {
    if (status == OrderStatus.CANCELLED) return this;
    try {
      return new Order(
          orderId, items, status.cancel(), version + 1, "PENDING", version + 1, unavailableItems);
    } catch (IllegalArgumentException e) {
      throw new BusinessException(BusinessException.Kind.CONFLICT, e.getMessage());
    }
  }

  public Order result(boolean reserved, List<Shortage> shortages) {
    if (status != OrderStatus.PENDING) return this;
    return new Order(
        orderId, items, status.reservationResult(reserved), version + 1, null, 0, shortages);
  }

  public Order released(long requestVersion) {
    if (status != OrderStatus.CANCELLED || cancellationVersion != requestVersion)
      throw new BusinessException(BusinessException.Kind.CONFLICT, "UNEXPECTED_RELEASE");
    return "COMPLETED".equals(inventoryCancellationStatus)
        ? this
        : new Order(
            orderId,
            items,
            status,
            version + 1,
            "COMPLETED",
            cancellationVersion,
            unavailableItems);
  }
}
