package com.roshka.inventory.application.port.in;

import com.roshka.inventory.application.model.ProductSnapshot;

public interface CreateProductUseCase {
  record Command(String sku, String name, long initialStock) {}

  ProductSnapshot create(Command command);
}
