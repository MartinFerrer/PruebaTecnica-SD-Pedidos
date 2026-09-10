package com.roshka.order.domain;

public final class BusinessException extends RuntimeException {
  public enum Kind {
    INVALID,
    NOT_FOUND,
    CONFLICT
  }

  private final Kind kind;

  public BusinessException(Kind kind, String code) {
    super(code);
    this.kind = kind;
  }

  public Kind kind() {
    return kind;
  }
}
