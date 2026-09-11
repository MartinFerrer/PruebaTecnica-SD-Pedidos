package com.roshka.platform.web;

import java.util.Objects;

/** Stable RFC 9457-compatible error body used by every HTTP adapter. */
public record ErrorResponse(String type, String title, int status, String code) {
  public ErrorResponse {
    type = Objects.requireNonNull(type, "type");
    title = requireText(title, "title");
    code = requireText(code, "code");
    if (status < 400 || status > 599) {
      throw new IllegalArgumentException("status must be an HTTP error status");
    }
  }

  public static ErrorResponse of(int status, String code) {
    return new ErrorResponse("about:blank", code, status, code);
  }

  private static String requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    return value;
  }
}
