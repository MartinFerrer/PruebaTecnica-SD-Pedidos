package com.roshka.platform.web;

import com.roshka.platform.messaging.MessageContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@org.springframework.core.annotation.Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE)
public class CorrelationFilter extends OncePerRequestFilter {

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String correlation = request.getHeader("X-Correlation-Id");
		if (correlation == null || !correlation.matches("[A-Za-z0-9._-]{1,128}")) {
			correlation = UUID.randomUUID().toString();
		}
		String trace = request.getHeader("traceparent");
		if (trace == null || !trace.matches("00-(?!0{32})[0-9a-f]{32}-(?!0{16})[0-9a-f]{16}-[0-9a-f]{2}")) {
			trace = MessageContext.newTrace();
		}
		response.setHeader("X-Correlation-Id", correlation);
		response.setHeader("X-Service-Instance", instanceId());
		try (var scope = new MessageContext(correlation, UUID.randomUUID().toString(), trace).open()) {
			chain.doFilter(request, response);
		}
	}

	private String instanceId() {
		String instance = System.getenv("INSTANCE_ID");
		if (instance == null || instance.isBlank()) {
			instance = System.getenv("HOSTNAME");
		}
		return instance == null || instance.isBlank() ? "local" : instance;
	}

}
