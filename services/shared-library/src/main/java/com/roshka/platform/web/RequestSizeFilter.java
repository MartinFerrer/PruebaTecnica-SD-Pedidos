package com.roshka.platform.web;

import com.roshka.platform.json.JsonCodec;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.filter.OncePerRequestFilter;

/** Bounds even chunked request bodies before JSON parsing and idempotency admission. */
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public final class RequestSizeFilter extends OncePerRequestFilter {

	public static final int MAX_BODY_BYTES = 65_536;

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		byte[] body = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
		if (body.length > MAX_BODY_BYTES) {
			response.setStatus(413);
			response.setContentType("application/problem+json");
			response.getWriter().write(new JsonCodec().write(ErrorResponse.of(413, "REQUEST_TOO_LARGE")));
			return;
		}
		chain.doFilter(new HttpServletRequestWrapper(request) {
			@Override
			public ServletInputStream getInputStream() {
				var input = new ByteArrayInputStream(body);
				return new ServletInputStream() {
					@Override
					public int read() {
						return input.read();
					}

					@Override
					public boolean isFinished() {
						return input.available() == 0;
					}

					@Override
					public boolean isReady() {
						return true;
					}

					@Override
					public void setReadListener(ReadListener listener) {
						throw new UnsupportedOperationException("Blocking MVC request stream");
					}
				};
			}
		}, response);
	}
}
