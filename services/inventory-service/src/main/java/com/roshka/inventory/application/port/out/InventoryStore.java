package com.roshka.inventory.application.port.out;

import com.roshka.inventory.application.port.in.Inventory.Change;
import com.roshka.inventory.domain.Product;
import java.util.Optional;
import java.util.List;
import java.util.UUID;

public interface InventoryStore {
  void lockIdentity(String identity);

  boolean skuExists(String sku);

  Optional<Product> find(UUID id, boolean lock);

  List<Product> findAll();

  void insert(Product product);

  void update(Product product);

  Optional<Change> movement(UUID movementId);

  void movement(String operation, Product before, Product after, Change result);
}
