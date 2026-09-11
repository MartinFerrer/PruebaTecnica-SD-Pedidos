package com.roshka.inventory.adapter.in.rest;

import java.util.UUID;

public record StockChangeResponse(
    UUID productId,
    UUID movementId,
    long quantity,
    long previousOnHand,
    long onHand,
    long reserved,
    long available,
    long version,
    String reason) {}
