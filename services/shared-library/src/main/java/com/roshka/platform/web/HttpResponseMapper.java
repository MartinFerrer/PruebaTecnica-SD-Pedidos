package com.roshka.platform.web;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/** Maps the transport-neutral idempotency reply to Spring MVC. */
public final class HttpResponseMapper {
  private HttpResponseMapper() {}

  public static ResponseEntity<String> toResponse(IdempotencyExecutor.Reply reply) {
    var headers = new HttpHeaders();
    headers.setContentType(
        reply.status() >= 400 ? MediaType.APPLICATION_PROBLEM_JSON : MediaType.APPLICATION_JSON);
    if (reply.status() == 503) {
      headers.set("Retry-After", "1");
    }
    return new ResponseEntity<>(reply.body(), headers, HttpStatusCode.valueOf(reply.status()));
  }
}
