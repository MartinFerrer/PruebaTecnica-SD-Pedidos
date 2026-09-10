package com.roshka.inventory.adapter.in.rest;

import com.roshka.inventory.application.port.in.Inventory;
import com.roshka.inventory.configuration.RequestTransactions;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.Map;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public class InventoryController {
  record Create(
      @NotBlank @Size(max = 100) String sku,
      @NotBlank @Size(max = 200) String name,
      @NotNull @Min(0) @Max(1000000000) Long initialStock) {}

  record Restock(
      @NotNull UUID movementId,
      @NotNull @Min(1) @Max(1000000000) Long quantity,
      @NotBlank @Size(max = 200) String reason) {}

  record Recount(
      @NotNull UUID productId,
      @NotNull @Min(0) @Max(1000000000) Long stock,
      @NotNull @Min(1) Long expectedVersion,
      @NotBlank @Size(max = 200) String reason) {}

  private final Inventory inventory;
  private final RequestTransactions tx;

  public InventoryController(Inventory inventory, RequestTransactions tx) {
    this.inventory = inventory;
    this.tx = tx;
  }

  @PostMapping("/products")
  ResponseEntity<String> create(
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      @Valid @RequestBody Create c) {
    return Errors.response(tx.write("create-product", key, c, 201, () -> inventory.create(createCommand(c))));
  }

  @GetMapping("/products/{id}/stock")
  Map<String, Object> get(@PathVariable UUID id) {
    return tx.read(() -> stockView(inventory.get(id)));
  }

  @GetMapping("/products")
  List<ProductView> list() {
    return tx.read(() -> inventory.list().stream().map(InventoryController::productView).toList());
  }

  @PostMapping("/products/{id}/restocks")
  ResponseEntity<String> restock(
      @PathVariable UUID id,
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      @Valid @RequestBody Restock c) {
    return Errors.response(
        tx.write("restock-product", key, Map.of("productId", id, "body", c), 201,
            () -> inventory.restock(id, restockCommand(c))));
  }

  @PutMapping("/products")
  ResponseEntity<String> recount(
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      @Valid @RequestBody Recount c) {
    return Errors.response(tx.write("recount-product", key, c, 200, () -> inventory.recount(recountCommand(c))));
  }

  private static Inventory.Create createCommand(Create c) {
    return new Inventory.Create(c.sku(), c.name(), c.initialStock());
  }

  private static Inventory.Restock restockCommand(Restock c) {
    return new Inventory.Restock(c.movementId(), c.quantity(), c.reason());
  }

  private static Inventory.Recount recountCommand(Recount c) {
    return new Inventory.Recount(c.productId(), c.stock(), c.expectedVersion(), c.reason());
  }

  private static Map<String, Object> stockView(Inventory.View stock) {
    return Map.of("productId", stock.productId(), "onHand", stock.onHand(), "reserved", stock.reserved(),
        "available", stock.available(), "version", stock.version(), "updatedAt", stock.updatedAt());
  }

  private static ProductView productView(Inventory.View product) {
    return new ProductView(product.productId(), product.sku(), product.name(), product.onHand(),
        product.reserved(), product.available(), product.version(), product.updatedAt());
  }

  record ProductView(UUID productId, String sku, String name, long onHand, long reserved, long available,
      long version, java.time.Instant updatedAt) {}
}
