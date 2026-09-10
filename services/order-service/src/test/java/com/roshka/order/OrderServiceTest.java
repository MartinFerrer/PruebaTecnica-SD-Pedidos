package com.roshka.order;

import static org.assertj.core.api.Assertions.*;

import com.roshka.order.application.port.out.OrderStore;
import com.roshka.order.application.service.OrderService;
import com.roshka.order.domain.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class OrderServiceTest {
  final Map<UUID, Order> data = new HashMap<>();
  final List<String> events = new ArrayList<>();
  final UUID id = UUID.randomUUID();
  final OrderService service =
      new OrderService(
          new OrderStore() {
            public Optional<Order> find(UUID id, boolean lock) {
              return Optional.ofNullable(data.get(id));
            }

            public List<Order> findAll() {
              return new ArrayList<>(data.values());
            }

            public void save(Order order) {
              data.put(order.orderId(), order);
            }
          },
          (type, aggregate, version, payload) -> events.add(type),
          () -> id);

  @Test
  void creationConfirmationCancellationAndRelease() {
    service.create(List.of(new Order.Item(UUID.randomUUID(), 1)));
    service.result(id, 1, true, List.of());
    assertThat(service.get(id).status()).isEqualTo(OrderStatus.CONFIRMED);
    assertThat(service.cancel(id, "TEST").accepted()).isTrue();
    assertThat(service.cancel(id, "TEST").accepted()).isFalse();
    service.released(id, 3);
    service.released(id, 3);
    service.result(id, 1, true, List.of());
    assertThat(service.get(id).inventoryCancellationStatus()).isEqualTo("COMPLETED");
    assertThat(events).containsExactly("OrderCreated", "OrderCancelled");
  }

  @Test
  void missingOrderAndInvalidResultAreRejected() {
    assertThatThrownBy(() -> service.get(id)).isInstanceOf(BusinessException.class);
    assertThatThrownBy(() -> service.result(id, 5, true, List.of())).isInstanceOf(BusinessException.class);
  }

  @Test
  void rejectionPreservesAllShortages() {
    UUID product = UUID.randomUUID();
    service.create(List.of(new Order.Item(product, 2)));
    var shortages = List.of(new Order.Shortage(product, 2, 0, "INSUFFICIENT_STOCK"));
    service.result(id, 1, false, shortages);
    assertThat(service.get(id).unavailableItems()).isEqualTo(shortages);
    assertThatThrownBy(() -> service.cancel(id, "TEST")).isInstanceOf(BusinessException.class);
  }
}
