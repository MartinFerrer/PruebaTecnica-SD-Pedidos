package com.roshka.inventory.application.model;

import java.time.Instant;
import java.util.UUID;

public record ProductSnapshot(
    UUID productId,
    String sku,
    String name,
    long onHand,
    long reserved,
    long available,
    long version,
    Instant updatedAt) {}
