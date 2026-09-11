package com.roshka.inventory.application.service;

import com.roshka.inventory.application.model.StockChange;
import com.roshka.inventory.application.port.in.CancelReservationUseCase;
import com.roshka.inventory.application.port.in.ReserveStockUseCase;
import com.roshka.inventory.application.port.out.EventPublisherPort;
import com.roshka.inventory.application.port.out.InventoryStore;
import com.roshka.inventory.application.port.out.ReservationStore;
import com.roshka.inventory.domain.Product;
import com.roshka.inventory.domain.Reservation;
import com.roshka.inventory.domain.Stock;
import com.roshka.platform.observability.PlatformMetrics;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

public class ReservationService implements ReserveStockUseCase, CancelReservationUseCase {

	private final ReservationStore reservations;

	private final InventoryStore products;

	private final EventPublisherPort events;

	private final Clock clock;

	private final Supplier<UUID> ids;

	private final PlatformMetrics metrics;

	public ReservationService(ReservationStore reservations, InventoryStore products, EventPublisherPort events,
			Clock clock, Supplier<UUID> ids) {
		this(reservations, products, events, clock, ids, PlatformMetrics.noop());
	}

	public ReservationService(ReservationStore reservations, InventoryStore products, EventPublisherPort events,
			Clock clock, Supplier<UUID> ids, PlatformMetrics metrics) {
		this.reservations = reservations;
		this.products = products;
		this.events = events;
		this.clock = clock;
		this.ids = ids;
		this.metrics = metrics;
	}

	@Override
	public void reserve(UUID orderId, long version, List<Reservation.Item> items) {
		validateReservationRequest(version, items);
		reservations.lock(orderId);
		if (reservations.find(orderId).isPresent()) {
			metrics.increment("inventory_reservation_replays_total");
			return;
		}

		List<Map<String, Object>> shortages = new ArrayList<>();
		Map<UUID, Product> locked = new LinkedHashMap<>();
		for (var item : sorted(items)) {
			var product = products.find(item.productId(), true);
			long available = product.map(value -> value.stock().available()).orElse(0L);
			if (product.isEmpty() || available < item.quantity()) {
				shortages.add(Map.of("productId", item.productId(), "requested", item.quantity(), "available",
						available, "reason", product.isEmpty() ? "PRODUCT_NOT_FOUND" : "INSUFFICIENT_STOCK"));
			}
			product.ifPresent(value -> locked.put(value.productId(), value));
		}

		if (!shortages.isEmpty()) {
			metrics.increment("inventory_reservations_total", "outcome", "rejected");
			metrics.increment("unavailable_items_total", "reason", "insufficient_or_missing");
			reservations.save(new Reservation(orderId, "REJECTED", version, 1, items));
			events.publish("StockRejected", orderId, 1,
					Map.of("orderId", orderId, "requestOrderVersion", version, "unavailableItems", shortages));
			return;
		}

		for (var item : items) {
			change(locked.get(item.productId()), item.quantity(), true, orderId);
		}
		reservations.save(new Reservation(orderId, "RESERVED", version, 1, items));
		metrics.increment("inventory_reservations_total", "outcome", "reserved");
		events.publish("StockReserved", orderId, 1,
				Map.of("orderId", orderId, "requestOrderVersion", version, "items", items));
	}

	@Override
	public void cancel(UUID orderId, long version) {
		if (version < 2) {
			throw new IllegalArgumentException("INVALID_CANCEL_VERSION");
		}
		reservations.lock(orderId);
		var existing = reservations.find(orderId);
		if (existing.isPresent() && (existing.get().lastOrderVersion() >= version
				|| Set.of("RELEASED", "CANCELLED_BEFORE_RESERVATION").contains(existing.get().state()))) {
			metrics.increment("inventory_release_replays_total");
			return;
		}

		String outcome = "CANCELLED_BEFORE_RESERVATION";
		long nextVersion = 1;
		List<Reservation.Item> released = new ArrayList<>();
		if (existing.isPresent()) {
			var before = existing.get();
			nextVersion = before.version() + 1;
			outcome = "NOT_RESERVED";
			if (before.state().equals("RESERVED")) {
				for (var item : sorted(before.items())) {
					Product product = products.find(item.productId(), true)
						.orElseThrow(() -> new IllegalStateException("RESERVED_PRODUCT_NOT_FOUND"));
					change(product, item.quantity(), false, orderId);
					released.add(item);
				}
				outcome = "RELEASED";
			}
		}

		reservations.save(new Reservation(orderId, existing.isEmpty() ? "CANCELLED_BEFORE_RESERVATION" : "RELEASED",
				version, nextVersion, existing.map(Reservation::items).orElse(List.of())));
		 events.publish("StockReleased", orderId, nextVersion, Map.of("orderId", orderId, "requestOrderVersion", version,
				"outcome", outcome, "releasedItems", released));
		metrics.increment("inventory_releases_total", "outcome", outcome.toLowerCase());
	}

	private void change(Product before, long quantity, boolean reserve, UUID orderId) {
		Stock stock = reserve ? before.stock().reserve(quantity) : before.stock().release(quantity);
		Product after = before.withStock(stock, clock.instant());
		products.update(after);
		products.movement(reserve ? "RESERVE" : "RELEASE", before, after,
				new StockChange(after.productId(), ids.get(), quantity, before.stock().onHand(), stock.onHand(),
						stock.reserved(), stock.available(), stock.version(),
						(reserve ? "RESERVE:" : "RELEASE:") + orderId));
	}

	private static void validateReservationRequest(long version, List<Reservation.Item> items) {
		if (version != 1 || items.isEmpty() || items.size() > 100
				|| items.stream().map(Reservation.Item::productId).distinct().count() != items.size()) {
			throw new IllegalArgumentException("INVALID_RESERVATION");
		}
	}

	private static List<Reservation.Item> sorted(List<Reservation.Item> items) {
		return items.stream().sorted(Comparator.comparing(item -> item.productId().toString())).toList();
	}

}
