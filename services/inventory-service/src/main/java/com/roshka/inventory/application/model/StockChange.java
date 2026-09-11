package com.roshka.inventory.application.model;

import java.util.UUID;

public record StockChange(
    UUID productId,
    UUID movementId,
    long quantity,
    long previousOnHand,
    long onHand,
    long reserved,
    long available,
    long version,
    String reason) {}
