package com.roshka.order.application.port.in;

import com.roshka.order.domain.Order;
import java.util.List;
import java.util.UUID;

public interface CreateOrderUseCase {
  record Item(UUID productId, long quantity) {}

  record Command(List<Item> items) {
    public Command {
      items = List.copyOf(items);
    }
  }

  Order create(Command command);
}
