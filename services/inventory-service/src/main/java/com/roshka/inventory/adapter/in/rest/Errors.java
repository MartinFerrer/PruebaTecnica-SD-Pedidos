package com.roshka.inventory.adapter.in.rest;

import com.roshka.inventory.configuration.RequestTransactions;
import com.roshka.inventory.domain.BusinessException;
import org.springframework.dao.DataAccessException;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
public class Errors {
  private final RequestTransactions requests;

  public Errors(RequestTransactions requests) {
    this.requests = requests;
  }

  public static ResponseEntity<String> response(RequestTransactions.Reply reply) {
    var headers = new HttpHeaders();
    headers.setContentType(
        MediaType.parseMediaType(
            reply.status() >= 400 ? "application/problem+json" : "application/json"));
    if (reply.status() == 503) headers.set("Retry-After", "1");
    return new ResponseEntity<>(reply.body(), headers, HttpStatusCode.valueOf(reply.status()));
  }

  @ExceptionHandler(BusinessException.class)
  ResponseEntity<String> business(BusinessException e) {
    return response(
        requests.problem(
            switch (e.kind()) {
              case INVALID -> 400;
              case NOT_FOUND -> 404;
              case CONFLICT -> 409;
            },
            e.getMessage()));
  }

  @ExceptionHandler({
    org.springframework.web.bind.MethodArgumentNotValidException.class,
    org.springframework.http.converter.HttpMessageNotReadableException.class,
    org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
    IllegalArgumentException.class
  })
  ResponseEntity<String> invalid(Exception e) {
    return response(requests.problem(400, "INVALID_REQUEST"));
  }

  @ExceptionHandler(DataAccessException.class)
  ResponseEntity<String> unavailable(DataAccessException e) {
    return response(requests.problem(503, "DATABASE_UNAVAILABLE"));
  }
}
