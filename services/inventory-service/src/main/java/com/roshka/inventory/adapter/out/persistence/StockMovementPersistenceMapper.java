package com.roshka.inventory.adapter.out.persistence;

import com.roshka.inventory.application.model.StockChange;
import com.roshka.platform.json.JsonCodec;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
class StockMovementPersistenceMapper {
  private final JsonCodec json;

  StockMovementPersistenceMapper(JsonCodec json) {
    this.json = json;
  }

  StockChange fromJson(String value) {
    StoredStockChange stored = json.read(value, StoredStockChange.class);
    return new StockChange(
        stored.productId(),
        stored.movementId(),
        stored.quantity(),
        stored.previousOnHand(),
        stored.onHand(),
        stored.reserved(),
        stored.available(),
        stored.version(),
        stored.reason());
  }

  String toJson(StockChange change) {
    return json.write(
        new StoredStockChange(
            change.productId(),
            change.movementId(),
            change.quantity(),
            change.previousOnHand(),
            change.onHand(),
            change.reserved(),
            change.available(),
            change.version(),
            change.reason()));
  }

  private record StoredStockChange(
      UUID productId,
      UUID movementId,
      long quantity,
      long previousOnHand,
      long onHand,
      long reserved,
      long available,
      long version,
      String reason) {}
}
