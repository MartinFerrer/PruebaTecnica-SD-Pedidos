package com.roshka.platform.web;

import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;

/**
 * Common translation for transport and infrastructure failures.
 *
 * <p>Business exceptions remain owned and translated by each service-specific advice.
 */
public abstract class AbstractRestExceptionHandler {
  protected final RequestTransactions transactions;

  protected AbstractRestExceptionHandler(RequestTransactions transactions) {
    this.transactions = transactions;
  }

  @ExceptionHandler({
    org.springframework.web.bind.MethodArgumentNotValidException.class,
    org.springframework.http.converter.HttpMessageNotReadableException.class,
    org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
    IllegalArgumentException.class
  })
  protected ResponseEntity<String> invalidRequest(Exception failure) {
    return response(transactions.problem(400, "INVALID_REQUEST"));
  }

  @ExceptionHandler(DataAccessException.class)
  protected ResponseEntity<String> databaseUnavailable(DataAccessException failure) {
    return response(transactions.problem(503, "DATABASE_UNAVAILABLE"));
  }

  protected final ResponseEntity<String> response(IdempotencyExecutor.Reply reply) {
    return HttpResponseMapper.toResponse(reply);
  }
}
