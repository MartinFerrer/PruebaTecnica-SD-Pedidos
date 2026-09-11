package com.roshka.order.adapter.in.rest;

import com.roshka.order.application.port.in.CreateOrderUseCase;
import com.roshka.order.domain.Order;
import org.springframework.stereotype.Component;

@Component
public class OrderApiMapper {
  public CreateOrderUseCase.Command toCommand(OrderRequest.Create request) {
    return new CreateOrderUseCase.Command(
        request.items().stream()
            .map(item -> new CreateOrderUseCase.Item(item.productId(), item.quantity()))
            .toList());
  }

  public OrderResponse toResponse(Order order) {
    return new OrderResponse(
        order.orderId(),
        order.status().name(),
        order.items().stream()
            .map(item -> new OrderResponse.Line(item.productId(), item.quantity()))
            .toList(),
        order.unavailableItems().stream()
            .map(
                shortage ->
                    new OrderResponse.Shortage(
                        shortage.productId(),
                        shortage.requested(),
                        shortage.available(),
                        shortage.reason()))
            .toList(),
        order.inventoryCancellationStatus(),
        "/orders/" + order.orderId());
  }

  public String cancellationReason(OrderRequest.Cancel request) {
    return request == null || request.reason() == null ? "" : request.reason();
  }
}
