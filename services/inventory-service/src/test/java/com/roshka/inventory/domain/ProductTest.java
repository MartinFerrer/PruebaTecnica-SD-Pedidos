package com.roshka.inventory.domain;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProductTest {
  private final Stock stock = new Stock(1, 0, 1);
  private final Instant now = Instant.parse("2026-01-01T00:00:00Z");

  @Test
  void rejectsMissingOrInvalidIdentityAndMetadata() {
    assertThatThrownBy(() -> new Product(null, "SKU", "Name", stock, now))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Product(UUID.randomUUID(), " ", "Name", stock, now))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Product(UUID.randomUUID(), "SKU", " ", stock, now))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Product(UUID.randomUUID(), "SKU", "Name", null, now))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Product(UUID.randomUUID(), "SKU", "Name", stock, null))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
