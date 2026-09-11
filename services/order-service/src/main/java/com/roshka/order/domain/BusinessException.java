package com.roshka.order.domain;

import java.util.Objects;

public final class BusinessException extends RuntimeException {
  public enum Kind {
    NOT_FOUND,
    CONFLICT
  }

  private final Kind kind;
  private final String code;

  public BusinessException(Kind kind, String code) {
    super(requireCode(code));
    this.kind = Objects.requireNonNull(kind, "kind");
    this.code = code;
  }

  public Kind kind() {
    return kind;
  }

  public String code() {
    return code;
  }

  public static BusinessException notFound(String code) {
    return new BusinessException(Kind.NOT_FOUND, code);
  }

  public static BusinessException conflict(String code) {
    return new BusinessException(Kind.CONFLICT, code);
  }

  private static String requireCode(String code) {
    if (code == null || code.isBlank()) throw new IllegalArgumentException("code must not be blank");
    return code;
  }
}
