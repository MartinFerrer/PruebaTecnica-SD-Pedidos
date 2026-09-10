package com.roshka.inventory.domain;

import java.util.*;

public record Reservation(
    UUID orderId, String state, long lastOrderVersion, long version, List<Item> items) {
  public record Item(UUID productId, long quantity) {
    public Item {
      if (productId == null || quantity < 1 || quantity > Stock.MAX_QUANTITY)
        throw new IllegalArgumentException("INVALID_ITEM");
    }
  }

  public Reservation {
    items = List.copyOf(items);
  }
}
