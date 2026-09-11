package com.roshka.order.adapter.out.persistence;

import com.roshka.order.domain.Order;
import com.roshka.order.domain.OrderStatus;
import com.roshka.platform.json.JsonCodec;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
class OrderPersistenceMapper {
  private final JsonCodec json;

  OrderPersistenceMapper(JsonCodec json) {
    this.json = json;
  }

  Order toDomain(OrderEntity entity) {
    return new Order(
        entity.orderId,
        entity.items.stream()
            .map(item -> new Order.Item(item.productId, item.quantity))
            .toList(),
        OrderStatus.valueOf(entity.status),
        entity.version,
        entity.inventoryCancellationStatus,
        entity.cancellationVersion,
        List.of(json.read(entity.unavailableItems, Order.Shortage[].class)));
  }

  void updateEntity(Order order, OrderEntity entity, boolean fresh) {
    if (fresh) {
      entity.orderId = order.orderId();
      entity.items =
          new ArrayList<>(
              order.items().stream()
                  .map(item -> new OrderEntity.Line(item.productId(), item.quantity()))
                  .toList());
    }
    entity.status = order.status().name();
    entity.version = order.version();
    entity.inventoryCancellationStatus = order.inventoryCancellationStatus();
    entity.cancellationVersion = order.cancellationVersion();
    entity.unavailableItems = json.write(order.unavailableItems());
  }
}
