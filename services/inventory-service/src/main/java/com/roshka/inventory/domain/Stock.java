package com.roshka.inventory.domain;

public record Stock(long onHand, long reserved, long version) {
  public static final long MAX_QUANTITY = 1_000_000_000L;

  public Stock {
    if (onHand < 0 || onHand > MAX_QUANTITY || reserved < 0 || reserved > onHand || version < 1) {
      throw new IllegalArgumentException("INVALID_STOCK");
    }
  }

  public long available() {
    return onHand - reserved;
  }

  public Stock restock(long quantity) {
    positive(quantity);
    return new Stock(Math.addExact(onHand, quantity), reserved, Math.incrementExact(version));
  }

  public Stock recount(long quantity, long expectedVersion) {
    if (version != expectedVersion) throw new IllegalArgumentException("STOCK_VERSION_CONFLICT");
    if (quantity < reserved) throw new IllegalArgumentException("STOCK_BELOW_RESERVED");
    return quantity == onHand ? this : new Stock(quantity, reserved, Math.incrementExact(version));
  }

  public Stock reserve(long quantity) {
    positive(quantity);
    if (quantity > available()) throw new IllegalArgumentException("INSUFFICIENT_STOCK");
    return new Stock(onHand, reserved + quantity, Math.incrementExact(version));
  }

  public Stock release(long quantity) {
    positive(quantity);
    if (quantity > reserved) throw new IllegalArgumentException("INVALID_RELEASE");
    return new Stock(onHand, reserved - quantity, Math.incrementExact(version));
  }

  private static void positive(long quantity) {
    if (quantity <= 0 || quantity > MAX_QUANTITY)
      throw new IllegalArgumentException("INVALID_QUANTITY");
  }
}
