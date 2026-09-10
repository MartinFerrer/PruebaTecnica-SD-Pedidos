package com.roshka.inventory.application.service;

import com.roshka.inventory.application.port.in.*;
import com.roshka.inventory.application.port.out.*;
import com.roshka.inventory.domain.*;
import java.time.Clock;
import java.util.*;
import java.util.function.Supplier;

public class ReservationService implements Reservations {
  private final ReservationStore reservations;
  private final InventoryStore products;
  private final Events events;
  private final Clock clock;
  private final Supplier<UUID> ids;

  public ReservationService(
      ReservationStore reservations,
      InventoryStore products,
      Events events,
      Clock clock,
      Supplier<UUID> ids) {
    this.reservations = reservations;
    this.products = products;
    this.events = events;
    this.clock = clock;
    this.ids = ids;
  }

  public void reserve(UUID orderId, long version, List<Reservation.Item> items) {
    if (version != 1
        || items.isEmpty()
        || items.size() > 100
        || items.stream().map(Reservation.Item::productId).distinct().count() != items.size())
      throw new IllegalArgumentException("INVALID_RESERVATION");
    reservations.lock(orderId);
    if (reservations.find(orderId).isPresent()) return;
    List<Map<String, Object>> shortages = new ArrayList<>();
    Map<UUID, Product> locked = new LinkedHashMap<>();
    for (var item :
        items.stream().sorted(Comparator.comparing(i -> i.productId().toString())).toList()) {
      var product = products.find(item.productId(), true);
      long available = product.map(p -> p.stock().available()).orElse(0L);
      if (product.isEmpty() || available < item.quantity()) {
        shortages.add(
            Map.of(
                "productId",
                item.productId(),
                "requested",
                item.quantity(),
                "available",
                available,
                "reason",
                product.isEmpty() ? "PRODUCT_NOT_FOUND" : "INSUFFICIENT_STOCK"));
      }
      product.ifPresent(p -> locked.put(p.productId(), p));
    }
    if (!shortages.isEmpty()) {
      reservations.save(new Reservation(orderId, "REJECTED", version, 1, items));
      events.append(
          "StockRejected",
          orderId,
          1,
          Map.of(
              "orderId", orderId, "requestOrderVersion", version, "unavailableItems", shortages));
      return;
    }
    for (var item : items) change(locked.get(item.productId()), item.quantity(), true, orderId);
    reservations.save(new Reservation(orderId, "RESERVED", version, 1, items));
    events.append(
        "StockReserved",
        orderId,
        1,
        Map.of("orderId", orderId, "requestOrderVersion", version, "items", items));
  }

  public void cancel(UUID orderId, long version) {
    if (version < 2) throw new IllegalArgumentException("INVALID_CANCEL_VERSION");
    reservations.lock(orderId);
    var existing = reservations.find(orderId);
    if (existing.isPresent()
        && (existing.get().lastOrderVersion() >= version
            || Set.of("RELEASED", "CANCELLED_BEFORE_RESERVATION").contains(existing.get().state())))
      return;
    String outcome = "CANCELLED_BEFORE_RESERVATION";
    long nextVersion = 1;
    List<Reservation.Item> released = new ArrayList<>();
    if (existing.isPresent()) {
      var before = existing.get();
      nextVersion = before.version() + 1;
      outcome = "NOT_RESERVED";
      if (before.state().equals("RESERVED")) {
        for (var item :
            before.items().stream().sorted(Comparator.comparing(i -> i.productId().toString())).toList()) {
          Product product = products.find(item.productId(), true).orElseThrow();
          change(product, item.quantity(), false, orderId);
          released.add(item);
        }
        outcome = "RELEASED";
      }
    }
    reservations.save(
        new Reservation(
            orderId,
            existing.isEmpty() ? "CANCELLED_BEFORE_RESERVATION" : "RELEASED",
            version,
            nextVersion,
            existing.map(Reservation::items).orElse(List.of())));
    events.append(
        "StockReleased",
        orderId,
        nextVersion,
        Map.of(
            "orderId",
            orderId,
            "requestOrderVersion",
            version,
            "outcome",
            outcome,
            "releasedItems",
            released));
  }

  private void change(Product before, long quantity, boolean reserve, UUID orderId) {
    Stock stock = reserve ? before.stock().reserve(quantity) : before.stock().release(quantity);
    Product after = before.withStock(stock, clock.instant());
    products.update(after);
    products.movement(
        reserve ? "RESERVE" : "RELEASE",
        before,
        after,
        new Inventory.Change(
            after.productId(),
            ids.get(),
            quantity,
            before.stock().onHand(),
            stock.onHand(),
            stock.reserved(),
            stock.available(),
            stock.version(),
            (reserve ? "RESERVE:" : "RELEASE:") + orderId));
  }
}
