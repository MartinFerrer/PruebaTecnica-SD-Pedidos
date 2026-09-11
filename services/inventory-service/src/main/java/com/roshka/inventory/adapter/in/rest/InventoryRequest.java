package com.roshka.inventory.adapter.in.rest;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public final class InventoryRequest {
  private InventoryRequest() {}

  public record Create(
      @NotBlank @Size(max = 100) String sku,
      @NotBlank @Size(max = 200) String name,
      @NotNull @Min(0) @Max(1_000_000_000) Long initialStock) {}

  public record Restock(
      @NotNull UUID movementId,
      @NotNull @Min(1) @Max(1_000_000_000) Long quantity,
      @NotBlank @Size(max = 200) String reason) {}

  public record Recount(
      @NotNull UUID productId,
      @NotNull @Min(0) @Max(1_000_000_000) Long stock,
      @NotNull @Min(1) Long expectedVersion,
      @NotBlank @Size(max = 200) String reason) {}
}
