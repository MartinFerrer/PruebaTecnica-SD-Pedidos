package com.roshka.inventory.application.port.in;

import com.roshka.inventory.application.model.StockChange;
import java.util.UUID;

public interface RecountStockUseCase {
  record Command(UUID productId, long stock, long expectedVersion, String reason) {}

  StockChange recount(Command command);
}
