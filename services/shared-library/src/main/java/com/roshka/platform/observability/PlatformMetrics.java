package com.roshka.platform.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.atomic.AtomicLong;

/** Technical metrics with bounded labels; business code provides only bounded values. */
public final class PlatformMetrics {

	private final MeterRegistry registry;

	private final AtomicLong outboxPending = new AtomicLong();

	public PlatformMetrics(MeterRegistry registry) {
		this.registry = registry;
		registry.gauge("outbox_pending", outboxPending);
	}

	public static PlatformMetrics noop() {
		return new PlatformMetrics(new SimpleMeterRegistry());
	}

	public void increment(String name, String... tags) {
		Counter.builder(name).tags(tags).register(registry).increment();
	}

	public void outboxPending(long value) {
		outboxPending.set(value);
	}

}
