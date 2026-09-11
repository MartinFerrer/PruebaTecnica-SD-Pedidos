package com.roshka.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.roshka.order.application.port.in.ApplyReservationResultUseCase;
import com.roshka.order.application.port.in.CompleteInventoryCancellationUseCase;
import com.roshka.order.application.port.in.CreateOrderUseCase;
import com.roshka.order.application.port.out.OrderStore;
import com.roshka.order.application.service.OrderService;
import com.roshka.order.domain.BusinessException;
import com.roshka.order.domain.Order;
import com.roshka.order.domain.OrderStatus;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OrderServiceTest {

	final Map<UUID, Order> data = new HashMap<>();

	final List<String> events = new ArrayList<>();

	final UUID id = UUID.randomUUID();

	final OrderService service = new OrderService(new OrderStore() {
		public Optional<Order> find(UUID id, boolean lock) {
			return Optional.ofNullable(data.get(id));
		}

		public List<Order> findAll() {
			return new ArrayList<>(data.values());
		}

		public void save(Order order) {
			data.put(order.orderId(), order);
		}
	}, (type, aggregate, version, payload) -> events.add(type), () -> id);

	@Test
	void creationConfirmationCancellationAndRelease() {
		create(UUID.randomUUID(), 1);
		service
			.apply(new ApplyReservationResultUseCase.Reserved(id, 1, reservationItems(service.findById(id).items())));
		assertThat(service.findById(id).status()).isEqualTo(OrderStatus.CONFIRMED);
		assertThat(service.cancel(id, "TEST").accepted()).isTrue();
		assertThat(service.cancel(id, "TEST").accepted()).isFalse();
		var release = new CompleteInventoryCancellationUseCase.Result(id, 3, "RELEASED",
				releaseItems(service.findById(id).items()));
		service.complete(release);
		service.complete(release);
		service
			.apply(new ApplyReservationResultUseCase.Reserved(id, 1, reservationItems(service.findById(id).items())));
		assertThat(service.findById(id).inventoryCancellationStatus()).isEqualTo("COMPLETED");
		assertThat(events).containsExactly("OrderCreated", "OrderCancelled");
	}

	@Test
	void missingOrderAndInvalidResultAreRejected() {
		assertThatThrownBy(() -> service.findById(id)).isInstanceOf(BusinessException.class);
		assertThatThrownBy(() -> service.apply(new ApplyReservationResultUseCase.Reserved(id, 5, List.of())))
			.isInstanceOf(BusinessException.class);
	}

	@Test
	void rejectionPreservesAllShortages() {
		UUID product = UUID.randomUUID();
		create(product, 2);
		var shortages = List.of(new ApplyReservationResultUseCase.Shortage(product, 2, 0, "INSUFFICIENT_STOCK"));
		service.apply(new ApplyReservationResultUseCase.Rejected(id, 1, shortages));
		assertThat(service.findById(id).unavailableItems()).hasSize(1);
		assertThatThrownBy(() -> service.cancel(id, "TEST")).isInstanceOf(BusinessException.class);
	}

	@Test
	void malformedShortageCannotRejectAnOrder() {
		UUID product = UUID.randomUUID();
		create(product, 2);

		assertThatThrownBy(() -> service.apply(new ApplyReservationResultUseCase.Rejected(id, 1,
				List.of(new ApplyReservationResultUseCase.Shortage(product, 2, -1, "UNKNOWN")))))
			.isInstanceOf(BusinessException.class)
			.hasMessage("REJECTION_ITEMS_MISMATCH");
		assertThat(service.findById(id).status()).isEqualTo(OrderStatus.PENDING);
	}

	@Test
	void confirmedCancellationRequiresEvidenceOfReleasedItems() {
		create(UUID.randomUUID(), 1);
		service
			.apply(new ApplyReservationResultUseCase.Reserved(id, 1, reservationItems(service.findById(id).items())));
		service.cancel(id, "TEST");

		assertThatThrownBy(() -> service
			.complete(new CompleteInventoryCancellationUseCase.Result(id, 3, "NOT_RESERVED", List.of())))
			.isInstanceOf(BusinessException.class)
			.hasMessage("RELEASE_ITEMS_MISMATCH");
	}

	private void create(UUID productId, long quantity) {
		service.create(new CreateOrderUseCase.Command(List.of(new CreateOrderUseCase.Item(productId, quantity))));
	}

	private static List<ApplyReservationResultUseCase.Item> reservationItems(List<Order.Item> items) {
		return items.stream()
			.map(item -> new ApplyReservationResultUseCase.Item(item.productId(), item.quantity()))
			.toList();
	}

	private static List<CompleteInventoryCancellationUseCase.Item> releaseItems(List<Order.Item> items) {
		return items.stream()
			.map(item -> new CompleteInventoryCancellationUseCase.Item(item.productId(), item.quantity()))
			.toList();
	}

}
