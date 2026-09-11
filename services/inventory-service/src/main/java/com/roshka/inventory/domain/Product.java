package com.roshka.inventory.domain;

import java.time.Instant;
import java.util.UUID;

public record Product(UUID productId, String sku, String name, Stock stock, Instant updatedAt) {
	public Product {
		if (productId == null || sku == null || sku.isBlank() || sku.length() > 100 || name == null || name.isBlank()
				|| name.length() > 200 || stock == null || updatedAt == null) {
			throw new IllegalArgumentException("INVALID_PRODUCT");
		}
	}

	public Product withStock(Stock value, Instant now) {
		return new Product(productId, sku, name, value, now);
	}
}
