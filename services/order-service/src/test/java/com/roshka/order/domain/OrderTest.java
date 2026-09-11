package com.roshka.order.domain;

import static org.assertj.core.api.Assertions.*;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OrderTest {
  final UUID id = UUID.randomUUID();
  final Order.Item item = new Order.Item(UUID.randomUUID(), 1);

  Order pending() {
    return new Order(id, List.of(item), OrderStatus.PENDING, 1, null, 0, List.of());
  }

  @Test
  void rejectsInvalidItems() {
    assertThatThrownBy(() -> new Order.Item(null, 1)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Order.Item(id, 0)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Order.Item(id, 1000000001)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Order(id, List.of(), OrderStatus.PENDING, 1, null, 0, List.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new Order(id, List.of(item, item), OrderStatus.PENDING, 1, null, 0, List.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new Order(
                    id,
                    java.util.Collections.nCopies(101, item),
                    OrderStatus.PENDING,
                    1,
                    null,
                    0,
                    List.of())).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void cancellationIsTerminalAndCompensationNeverRegresses() {
    Order cancelled = pending().cancel();
    assertThat(cancelled.cancel()).isSameAs(cancelled);
    assertThat(cancelled.result(true, List.of())).isSameAs(cancelled);
    Order completed = cancelled.released(2);
    assertThat(completed.inventoryCancellationStatus()).isEqualTo("COMPLETED");
    assertThat(completed.released(2)).isSameAs(completed);
    assertThat(completed.result(false, List.of())).isSameAs(completed);
    assertThatThrownBy(() -> cancelled.released(3)).isInstanceOf(BusinessException.class);
    assertThatThrownBy(() -> pending().released(2)).isInstanceOf(BusinessException.class);
  }

  @Test
  void rejectionCannotBeCancelledAndConfirmationCan() {
    Order rejected =
        pending().result(
                false, List.of(new Order.Shortage(item.productId(), 1, 0, "INSUFFICIENT_STOCK")));
    assertThatThrownBy(rejected::cancel).isInstanceOf(BusinessException.class);
    assertThat(pending().result(true, List.of()).cancel().cancellationVersion()).isEqualTo(3);
  }
}
