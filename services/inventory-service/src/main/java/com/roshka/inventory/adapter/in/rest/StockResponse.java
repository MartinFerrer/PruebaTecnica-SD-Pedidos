package com.roshka.inventory.adapter.in.rest;

import java.time.Instant;
import java.util.UUID;

public record StockResponse(
    UUID productId,
    long onHand,
    long reserved,
    long available,
    long version,
    Instant updatedAt) {}
