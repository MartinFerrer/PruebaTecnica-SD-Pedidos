package com.roshka.inventory.application.service;

import com.roshka.inventory.application.model.ProductSnapshot;
import com.roshka.inventory.application.model.StockChange;
import com.roshka.inventory.application.port.in.CreateProductUseCase;
import com.roshka.inventory.application.port.in.FindInventoryQuery;
import com.roshka.inventory.application.port.in.RecountStockUseCase;
import com.roshka.inventory.application.port.in.RestockProductUseCase;
import com.roshka.inventory.application.port.out.EventPublisherPort;
import com.roshka.inventory.application.port.out.InventoryStore;
import com.roshka.inventory.domain.BusinessException;
import com.roshka.inventory.domain.Product;
import com.roshka.inventory.domain.Stock;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

public class InventoryService
    implements CreateProductUseCase, FindInventoryQuery, RestockProductUseCase, RecountStockUseCase {
  private final InventoryStore store;
  private final EventPublisherPort events;
  private final Clock clock;
  private final Supplier<UUID> ids;

  public InventoryService(
      InventoryStore store, EventPublisherPort events, Clock clock, Supplier<UUID> ids) {
    this.store = store;
    this.events = events;
    this.clock = clock;
    this.ids = ids;
  }

  @Override
  public ProductSnapshot create(CreateProductUseCase.Command command) {
    store.lockIdentity("sku:" + command.sku());
    if (store.skuExists(command.sku())) {
      throw BusinessException.conflict("SKU_EXISTS");
    }

    Product product =
        new Product(
            ids.get(),
            command.sku(),
            command.name(),
            new Stock(command.initialStock(), 0, 1),
            clock.instant());
    store.insert(product);

    StockChange change =
        change(product, null, ids.get(), command.initialStock(), "INITIAL");
    store.movement("CREATE", null, product, change);
    events.publish("ProductStockCreated", product.productId(), 1, snapshot(product));
    return snapshot(product);
  }

  @Override
  public ProductSnapshot findById(UUID productId) {
    return snapshot(product(productId, false));
  }

  @Override
  public List<ProductSnapshot> findAll() {
    return store.findAll().stream().map(InventoryService::snapshot).toList();
  }

  @Override
  public StockChange restock(UUID productId, RestockProductUseCase.Command command) {
    store.lockIdentity("movement:" + command.movementId());
    var existing = store.movement(command.movementId());
    if (existing.isPresent()) {
      StockChange value = existing.get();
      if (!value.productId().equals(productId)
          || value.quantity() != command.quantity()
          || !value.reason().equals(command.reason())) {
        throw BusinessException.conflict("MOVEMENT_CONFLICT");
      }
      return value;
    }

    Product before = product(productId, true);
    Stock next = before.stock().restock(command.quantity());
    Product after = before.withStock(next, clock.instant());
    StockChange result =
        change(after, before, command.movementId(), command.quantity(), command.reason());
    store.update(after);
    store.movement("RESTOCK", before, after, result);
    events.publish("ProductStockReplenished", productId, next.version(), result);
    return result;
  }

  @Override
  public StockChange recount(RecountStockUseCase.Command command) {
    Product before = product(command.productId(), true);
    Stock next = before.stock().recount(command.stock(), command.expectedVersion());
    Product after = before.withStock(next, clock.instant());
    StockChange result =
        change(
            after,
            before,
            ids.get(),
            command.stock() - before.stock().onHand(),
            command.reason());
    if (!next.equals(before.stock())) {
      store.update(after);
      store.movement("RECOUNT", before, after, result);
      events.publish("ProductStockUpdated", after.productId(), next.version(), result);
    }
    return result;
  }

  private Product product(UUID id, boolean lock) {
    return store.find(id, lock)
        .orElseThrow(() -> BusinessException.notFound("PRODUCT_NOT_FOUND"));
  }

  public static ProductSnapshot snapshot(Product product) {
    return new ProductSnapshot(
        product.productId(),
        product.sku(),
        product.name(),
        product.stock().onHand(),
        product.stock().reserved(),
        product.stock().available(),
        product.stock().version(),
        product.updatedAt());
  }

  private StockChange change(
      Product product, Product before, UUID movementId, long quantity, String reason) {
    return new StockChange(
        product.productId(),
        movementId,
        quantity,
        before == null ? 0 : before.stock().onHand(),
        product.stock().onHand(),
        product.stock().reserved(),
        product.stock().available(),
        product.stock().version(),
        reason);
  }
}
