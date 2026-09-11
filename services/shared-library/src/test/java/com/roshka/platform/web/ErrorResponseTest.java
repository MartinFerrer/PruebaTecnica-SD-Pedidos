package com.roshka.platform.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ErrorResponseTest {
  @Test
  void createsAStableProblemDetailsShape() {
    assertThat(ErrorResponse.of(409, "IDEMPOTENCY_CONFLICT"))
        .isEqualTo(
            new ErrorResponse(
                "about:blank", "IDEMPOTENCY_CONFLICT", 409, "IDEMPOTENCY_CONFLICT"));
  }

  @Test
  void rejectsInvalidErrorDefinitions() {
    assertThatThrownBy(() -> ErrorResponse.of(200, "NOT_AN_ERROR"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ErrorResponse.of(400, " "))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
