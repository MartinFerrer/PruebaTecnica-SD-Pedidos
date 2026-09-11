package com.roshka.order.application.service;

import com.roshka.order.application.port.in.ApplyReservationResultUseCase;
import com.roshka.order.application.port.in.CancelOrderUseCase;
import com.roshka.order.application.port.in.CompleteInventoryCancellationUseCase;
import com.roshka.order.application.port.in.CreateOrderUseCase;
import com.roshka.order.application.port.in.FindOrdersQuery;
import com.roshka.order.application.port.out.EventPublisherPort;
import com.roshka.order.application.port.out.OrderStore;
import com.roshka.order.domain.BusinessException;
import com.roshka.order.domain.Order;
import com.roshka.order.domain.OrderStatus;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

public class OrderService implements CreateOrderUseCase, FindOrdersQuery, CancelOrderUseCase,
		ApplyReservationResultUseCase, CompleteInventoryCancellationUseCase {

	private final OrderStore store;

	private final EventPublisherPort events;

	private final Supplier<UUID> ids;

	public OrderService(OrderStore store, EventPublisherPort events, Supplier<UUID> ids) {
		this.store = store;
		this.events = events;
		this.ids = ids;
	}

	@Override
	public Order create(CreateOrderUseCase.Command command) {
		List<Order.Item> items = command.items()
			.stream()
			.map(item -> new Order.Item(item.productId(), item.quantity()))
			.toList();
		Order order = new Order(ids.get(), items, OrderStatus.PENDING, 1, null, 0, List.of());
		store.save(order);
		events.publish("OrderCreated", order.orderId(), 1,
				Map.of("orderId", order.orderId(), "items", command.items()));
		return order;
	}

	@Override
	public Order findById(UUID orderId) {
		return find(orderId, false);
	}

	@Override
	public List<Order> findAll() {
		return store.findAll();
	}

	@Override
	public CancelOrderUseCase.Result cancel(UUID orderId, String reason) {
		Order before = find(orderId, true);
		Order after = before.cancel();
		if (after != before) {
			store.save(after);
			events.publish("OrderCancelled", orderId, after.version(), Map.of("orderId", orderId, "reason", reason));
		}
		return new CancelOrderUseCase.Result(after, after != before);
	}

	@Override
	public void apply(ApplyReservationResultUseCase.Result result) {
		if (result.requestOrderVersion() != 1) {
			throw BusinessException.conflict("UNEXPECTED_RESERVATION_RESULT");
		}

		Order before = find(result.orderId(), true);
		Order after = switch (result) {
			case ApplyReservationResultUseCase.Reserved reserved -> {
				List<Order.Item> items = reserved.items()
					.stream()
					.map(item -> new Order.Item(item.productId(), item.quantity()))
					.toList();
				validateReserved(before, items);
				yield before.result(true, List.of());
			}
			case ApplyReservationResultUseCase.Rejected rejected -> {
				List<Order.Shortage> shortages = rejected.shortages()
					.stream()
					.map(shortage -> new Order.Shortage(shortage.productId(), shortage.requested(),
							shortage.available(), shortage.reason()))
					.toList();
				validateRejected(before, shortages);
				yield before.result(false, shortages);
			}
		};
		if (after != before) {
			store.save(after);
		}
	}

	@Override
	public void complete(CompleteInventoryCancellationUseCase.Result result) {
		Order before = find(result.orderId(), true);
		List<Order.Item> releasedItems = result.releasedItems()
			.stream()
			.map(item -> new Order.Item(item.productId(), item.quantity()))
			.toList();
		validateRelease(before, result.outcome(), releasedItems);
		Order after = before.released(result.requestOrderVersion());
		if (after != before) {
			store.save(after);
		}
	}

	private Order find(UUID orderId, boolean lock) {
		return store.find(orderId, lock).orElseThrow(() -> BusinessException.notFound("ORDER_NOT_FOUND"));
	}

	private static void validateReserved(Order order, List<Order.Item> reservedItems) {
		if (reservedItems.size() != order.items().size()
				|| !new HashSet<>(reservedItems).equals(new HashSet<>(order.items()))) {
			throw BusinessException.conflict("RESERVATION_ITEMS_MISMATCH");
		}
	}

	private static void validateRejected(Order order, List<Order.Shortage> shortages) {
		Map<UUID, Order.Item> requested = new HashMap<>();
		order.items().forEach(item -> requested.put(item.productId(), item));
		if (shortages.isEmpty()
				|| shortages.stream().map(Order.Shortage::productId).distinct().count() != shortages.size()
				|| shortages.stream().anyMatch(shortage -> {
					Order.Item item = requested.get(shortage.productId());
					return item == null || shortage.requested() != item.quantity() || shortage.available() < 0
							|| shortage.available() >= shortage.requested()
							|| !Set.of("INSUFFICIENT_STOCK", "PRODUCT_NOT_FOUND").contains(shortage.reason())
							|| ("PRODUCT_NOT_FOUND".equals(shortage.reason()) && shortage.available() != 0);
				})) {
			throw BusinessException.conflict("REJECTION_ITEMS_MISMATCH");
		}
	}

	private static void validateRelease(Order order, String outcome, List<Order.Item> releasedItems) {
		boolean released = "RELEASED".equals(outcome);
		boolean noReservation = "NOT_RESERVED".equals(outcome) || "CANCELLED_BEFORE_RESERVATION".equals(outcome);
		boolean itemsMatch = releasedItems.size() == order.items().size()
				&& new HashSet<>(releasedItems).equals(new HashSet<>(order.items()));
		if ((!released && !noReservation) || (order.cancellationVersion() >= 3 && !released)
				|| (released && !itemsMatch) || (noReservation && !releasedItems.isEmpty())) {
			throw BusinessException.conflict("RELEASE_ITEMS_MISMATCH");
		}
	}

}
