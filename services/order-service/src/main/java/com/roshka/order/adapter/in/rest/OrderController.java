package com.roshka.order.adapter.in.rest;

import com.roshka.order.application.port.in.CancelOrderUseCase;
import com.roshka.order.application.port.in.CreateOrderUseCase;
import com.roshka.order.application.port.in.FindOrdersQuery;
import com.roshka.platform.json.JsonCodec;
import com.roshka.platform.web.HttpResponseMapper;
import com.roshka.platform.web.RequestTransactions;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class OrderController {
  private final CreateOrderUseCase createOrder;
  private final FindOrdersQuery findOrders;
  private final CancelOrderUseCase cancelOrder;
  private final RequestTransactions transactions;
  private final OrderApiMapper mapper;
  private final JsonCodec json;

  public OrderController(
      CreateOrderUseCase createOrder,
      FindOrdersQuery findOrders,
      CancelOrderUseCase cancelOrder,
      RequestTransactions transactions,
      OrderApiMapper mapper,
      JsonCodec json) {
    this.createOrder = createOrder;
    this.findOrders = findOrders;
    this.cancelOrder = cancelOrder;
    this.transactions = transactions;
    this.mapper = mapper;
    this.json = json;
  }

  @PostMapping("/orders")
  ResponseEntity<String> create(
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      @Valid @RequestBody OrderRequest.Create request) {
    var reply =
        transactions.write(
            "create-order",
            key,
            request,
            202,
            () -> mapper.toResponse(createOrder.create(mapper.toCommand(request))));
    var response = HttpResponseMapper.toResponse(reply);
    if (reply.status() != 202) {
      return response;
    }

    var headers = new HttpHeaders();
    headers.putAll(response.getHeaders());
    String orderId = json.mapper.readTree(reply.body()).get("orderId").stringValue();
    headers.setLocation(java.net.URI.create("/orders/" + orderId));
    return new ResponseEntity<>(reply.body(), headers, response.getStatusCode());
  }

  @GetMapping("/orders/{id}")
  OrderResponse get(@PathVariable UUID id) {
    return transactions.read(() -> mapper.toResponse(findOrders.findById(id)));
  }

  @GetMapping("/orders")
  List<OrderResponse> list() {
    return transactions.read(
        () -> findOrders.findAll().stream().map(mapper::toResponse).toList());
  }

  @PostMapping("/orders/{id}/cancel")
  ResponseEntity<String> cancel(
      @PathVariable UUID id,
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      @Valid @RequestBody OrderRequest.Cancel request) {
    String reason = mapper.cancellationReason(request);
    return HttpResponseMapper.toResponse(
        transactions.write(
            "cancel-order",
            key,
            Map.of("orderId", id, "reason", reason),
            202,
            () -> {
              var result = cancelOrder.cancel(id, reason);
              return new RequestTransactions.Result(
                  result.accepted() ? 202 : 200, mapper.toResponse(result.order()));
            }));
  }
}
