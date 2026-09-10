package com.roshka.order.adapter.in.rest;

import com.roshka.order.adapter.out.persistence.JsonCodec;
import com.roshka.order.application.port.in.Orders;
import com.roshka.order.configuration.RequestTransactions;
import com.roshka.order.domain.Order;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
public class OrderController {
  record Item(@NotNull UUID productId, @NotNull @Min(1) @Max(1000000000) Long quantity) {}

  record Create(@NotEmpty @Size(max = 100) List<@Valid Item> items) {}

  record Cancel(@Size(max = 200) String reason) {}

  record Line(UUID productId, long quantity) {}

  record Shortage(UUID productId, long requested, long available, String reason) {}

  record View(
      UUID orderId,
      String status,
      List<Line> items,
      List<Shortage> unavailableItems,
      String inventoryCancellationStatus,
      String statusUrl) {}

  static View view(Order o) {
    return new View(
        o.orderId(),
        o.status().name(),
        o.items().stream().map(i -> new Line(i.productId(), i.quantity())).toList(),
        o.unavailableItems().stream().map(i -> new Shortage(i.productId(), i.requested(), i.available(), i.reason()))
            .toList(),
        o.inventoryCancellationStatus(),
        "/orders/" + o.orderId());
  }

  private final Orders orders;
  private final RequestTransactions tx;
  private final JsonCodec json;

  public OrderController(Orders orders, RequestTransactions tx, JsonCodec json) {
    this.orders = orders;
    this.tx = tx;
    this.json = json;
  }

  @PostMapping("/orders")
  ResponseEntity<String> create(
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      @Valid @RequestBody Create c) {
    var reply = tx.write("create-order", key, c, 202, () -> view(orders.create(items(c))));
    var response = Errors.response(reply);
    if (reply.status() == 202) {
      var headers = new HttpHeaders();
      headers.putAll(response.getHeaders());
      headers.set("Location", "/orders/" + json.mapper.readTree(reply.body()).get("orderId").asText());
      return new ResponseEntity<>(reply.body(), headers, response.getStatusCode());
    }
    return response;
  }

  @GetMapping("/orders/{id}")
  View get(@PathVariable UUID id) {
    return tx.read(() -> view(orders.get(id)));
  }

  @GetMapping("/orders")
  List<View> list() {
    return tx.read(() -> orders.list().stream().map(OrderController::view).toList());
  }

  @PostMapping("/orders/{id}/cancel")
  ResponseEntity<String> cancel(
      @PathVariable UUID id,
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      @Valid @RequestBody Cancel c) {
    return Errors.response(
        tx.write(
            "cancel-order",
            key,
            Map.of("orderId", id, "reason", reason(c)),
            202,
            () -> {
              var result = orders.cancel(id, reason(c));
              return new RequestTransactions.Result(result.accepted() ? 202 : 200, view(result.order()));
            }));
  }

  private static List<Order.Item> items(Create c) {
    return c.items().stream().map(i -> new Order.Item(i.productId(), i.quantity())).toList();
  }

  private static String reason(Cancel c) {
    return c.reason() == null ? "" : c.reason();
  }
}
