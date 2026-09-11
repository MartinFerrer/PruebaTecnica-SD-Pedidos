package com.roshka.order.adapter.in.rest;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

public final class OrderRequest {
  private OrderRequest() {}

  public record Item(
      @NotNull UUID productId,
      @NotNull @Min(1) @Max(1_000_000_000) Long quantity) {}

  public record Create(@NotEmpty @Size(max = 100) List<@Valid Item> items) {}

  public record Cancel(@Size(max = 200) String reason) {}
}
