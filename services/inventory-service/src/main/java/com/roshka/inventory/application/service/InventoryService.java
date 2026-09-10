package com.roshka.inventory.application.service;

import static com.roshka.inventory.domain.BusinessException.Kind.*;

import com.roshka.inventory.application.port.in.Inventory;
import com.roshka.inventory.application.port.out.*;
import com.roshka.inventory.domain.*;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

public class InventoryService implements Inventory {
  private final InventoryStore store;
  private final Events events;
  private final Clock clock;
  private final Supplier<UUID> ids;

  public InventoryService(InventoryStore store, Events events, Clock clock, Supplier<UUID> ids) {
    this.store = store;
    this.events = events;
    this.clock = clock;
    this.ids = ids;
  }

  public View create(Create c) {
    store.lockIdentity("sku:" + c.sku());
    if (store.skuExists(c.sku())) throw new BusinessException(CONFLICT, "SKU_EXISTS");
    Product p =
        new Product(
            ids.get(), c.sku(), c.name(), new Stock(c.initialStock(), 0, 1), clock.instant());
    store.insert(p);
    Change change = change(p, null, ids.get(), c.initialStock(), "INITIAL");
    store.movement("CREATE", null, p, change);
    events.append("ProductStockCreated", p.productId(), 1, view(p));
    return view(p);
  }

  public View get(UUID id) {
    return view(product(id, false));
  }

  public List<View> list() {
    return store.findAll().stream().map(InventoryService::view).toList();
  }

  public Change restock(UUID id, Restock c) {
    store.lockIdentity("movement:" + c.movementId());
    var existing = store.movement(c.movementId());
    if (existing.isPresent()) {
      Change value = existing.get();
      if (!value.productId().equals(id)
          || value.quantity() != c.quantity()
          || !value.reason().equals(c.reason()))
        throw new BusinessException(CONFLICT, "MOVEMENT_CONFLICT");
      return value;
    }
    Product before = product(id, true);
    Stock next;
    try {
      next = before.stock().restock(c.quantity());
    } catch (IllegalArgumentException e) {
      throw new BusinessException(INVALID, e.getMessage());
    }
    Product after = before.withStock(next, clock.instant());
    Change result = change(after, before, c.movementId(), c.quantity(), c.reason());
    store.update(after);
    store.movement("RESTOCK", before, after, result);
    events.append("ProductStockReplenished", id, next.version(), result);
    return result;
  }

  public Change recount(Recount c) {
    Product before = product(c.productId(), true);
    Stock next;
    try {
      next = before.stock().recount(c.stock(), c.expectedVersion());
    } catch (IllegalArgumentException e) {
      throw new BusinessException(CONFLICT, e.getMessage());
    }
    Product after = before.withStock(next, clock.instant());
    Change result =
        change(after, before, ids.get(), c.stock() - before.stock().onHand(), c.reason());
    if (!next.equals(before.stock())) {
      store.update(after);
      store.movement("RECOUNT", before, after, result);
      events.append("ProductStockUpdated", after.productId(), next.version(), result);
    }
    return result;
  }

  private Product product(UUID id, boolean lock) {
    return store.find(id, lock).orElseThrow(() -> new BusinessException(NOT_FOUND, "PRODUCT_NOT_FOUND"));
  }

  public static View view(Product p) {
    return new View(
        p.productId(),
        p.sku(),
        p.name(),
        p.stock().onHand(),
        p.stock().reserved(),
        p.stock().available(),
        p.stock().version(),
        p.updatedAt());
  }

  private Change change(Product p, Product before, UUID movement, long quantity, String reason) {
    return new Change(
        p.productId(),
        movement,
        quantity,
        before == null ? 0 : before.stock().onHand(),
        p.stock().onHand(),
        p.stock().reserved(),
        p.stock().available(),
        p.stock().version(),
        reason);
  }
}
