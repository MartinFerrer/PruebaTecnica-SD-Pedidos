package com.roshka.inventory.adapter.in.rest;

import com.roshka.inventory.domain.BusinessException;
import com.roshka.platform.web.AbstractRestExceptionHandler;
import com.roshka.platform.web.RequestTransactions;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class Errors extends AbstractRestExceptionHandler {

	public Errors(RequestTransactions transactions) {
		super(transactions);
	}

	@ExceptionHandler(BusinessException.class)
	ResponseEntity<String> business(BusinessException failure) {
		return response(transactions.problem(status(failure.kind()), failure.code()));
	}

	private static int status(BusinessException.Kind kind) {
		return switch (kind) {
			case NOT_FOUND -> 404;
			case CONFLICT -> 409;
		};
	}

}
