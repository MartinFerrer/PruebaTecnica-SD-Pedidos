package com.roshka.inventory.domain;

import java.time.Instant;
import java.util.UUID;

public record Product(UUID productId, String sku, String name, Stock stock, Instant updatedAt) {
  public Product withStock(Stock value, Instant now) {
    return new Product(productId, sku, name, value, now);
  }
}
