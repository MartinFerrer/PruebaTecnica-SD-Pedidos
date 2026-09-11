package com.roshka.inventory.domain;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

class StockTest {
  @Test
  void restocksAccumulateAndPreserveReservations() {
    assertThat(new Stock(20, 4, 1).restock(5).restock(7)).isEqualTo(new Stock(32, 4, 3));
  }

  @Test
  void reservesThenReleasesWithoutChangingPhysicalStock() {
    Stock initial = new Stock(10, 0, 1);
    Stock reserved = initial.reserve(10);
    assertThat(reserved.available()).isZero();
    assertThat(reserved.release(10)).isEqualTo(new Stock(10, 0, 3));
  }

  @Test
  void refusesOverselling() {
    assertThatThrownBy(() -> new Stock(2, 1, 1).reserve(2)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void recountChecksVersionAndReservations() {
    Stock stock = new Stock(20, 4, 8);
    assertThat(stock.recount(30, 8)).isEqualTo(new Stock(30, 4, 9));
    assertThat(stock.recount(20, 8)).isEqualTo(stock);
    assertThatThrownBy(() -> stock.recount(30, 7))
        .isInstanceOfSatisfying(
            BusinessException.class,
            failure -> assertThat(failure.code()).isEqualTo("STOCK_VERSION_CONFLICT"));
    assertThatThrownBy(() -> stock.recount(3, 8))
        .isInstanceOfSatisfying(
            BusinessException.class,
            failure -> assertThat(failure.code()).isEqualTo("STOCK_BELOW_RESERVED"));
  }

  @Test
  void validatesBounds() {
    assertThatThrownBy(() -> new Stock(-1, 0, 1)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Stock(1, 2, 1)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Stock(1, 0, 0)).isInstanceOf(IllegalArgumentException.class);
    for (long invalid : new long[] {-1, 0, 1000000001L}) {
      assertThatThrownBy(() -> new Stock(10, 0, 1).restock(invalid)).isInstanceOf(IllegalArgumentException.class);
    }
    assertThatThrownBy(() -> new Stock(1000000000, 0, 1).restock(1)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Stock(10, 2, 1).release(3)).isInstanceOf(IllegalArgumentException.class);
  }
}
