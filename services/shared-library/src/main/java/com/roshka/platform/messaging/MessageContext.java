package com.roshka.platform.messaging;

import java.util.UUID;
import io.opentelemetry.api.trace.Span;
import org.slf4j.MDC;

public record MessageContext(String correlationId, String causationId, String traceparent) {

	private static final ThreadLocal<MessageContext> CURRENT = new ThreadLocal<>();

	public static MessageContext current() {
		var context = CURRENT.get();
		if (context != null) {
			return context;
		}
		String id = UUID.randomUUID().toString();
		return new MessageContext(id, id, newTrace());
	}

	public static String newTrace() {
		return "00-" + UUID.randomUUID().toString().replace("-", "") + "-"
				+ UUID.randomUUID().toString().replace("-", "").substring(0, 16) + "-01";
	}

	public Scope open() {
		var previous = CURRENT.get();
		CURRENT.set(this);
		putMdc(this);
		return new Scope(previous);
	}

	private static void putMdc(MessageContext context) {
		MDC.put("correlation_id", context.correlationId());
		MDC.put("causation_id", context.causationId());
		MDC.put("trace_id", context.traceparent().substring(3, 35));
		MDC.put("span_id", Span.current().getSpanContext().getSpanId());
	}

	public record Scope(MessageContext previous) implements AutoCloseable {
		@Override
		public void close() {
			if (previous == null) {
				CURRENT.remove();
				MDC.remove("correlation_id");
				MDC.remove("causation_id");
				MDC.remove("trace_id");
				MDC.remove("span_id");
			}
			else {
				CURRENT.set(previous);
				putMdc(previous);
			}
		}
	}
}
