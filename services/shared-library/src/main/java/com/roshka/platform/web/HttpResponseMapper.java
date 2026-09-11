package com.roshka.platform.web;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;

/** Maps the transport-neutral idempotency reply to Spring MVC. */
public final class HttpResponseMapper {

	private HttpResponseMapper() {
	}

	public static ResponseEntity<String> toResponse(IdempotencyExecutor.Reply reply) {
		var headers = new HttpHeaders();
		reply.headers().forEach(headers::set);
		return new ResponseEntity<>(reply.body(), headers, HttpStatusCode.valueOf(reply.status()));
	}

}
