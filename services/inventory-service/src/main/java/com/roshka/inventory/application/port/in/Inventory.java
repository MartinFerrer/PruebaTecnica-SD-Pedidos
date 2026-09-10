package com.roshka.inventory.application.port.in;

import java.time.Instant;
import java.util.UUID;

public interface Inventory {
  record Create(String sku, String name, long initialStock) {}

  record Restock(UUID movementId, long quantity, String reason) {}

  record Recount(UUID productId, long stock, long expectedVersion, String reason) {}

  record View(
      UUID productId,
      String sku,
      String name,
      long onHand,
      long reserved,
      long available,
      long version,
      Instant updatedAt) {}

  record Change(
      UUID productId,
      UUID movementId,
      long quantity,
      long previousOnHand,
      long onHand,
      long reserved,
      long available,
      long version,
      String reason) {}

  View create(Create command);

  View get(UUID id);

  java.util.List<View> list();

  Change restock(UUID id, Restock command);

  Change recount(Recount command);
}
