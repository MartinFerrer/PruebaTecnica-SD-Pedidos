package com.roshka.platform.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class MessageContextTest {

	@Test
	void nestedScopesRestoreThePreviousContextAndMdc() {
		var first = new MessageContext("first", "cause-1", "00-11111111111111111111111111111111-1111111111111111-01");
		var second = new MessageContext("second", "cause-2", "00-22222222222222222222222222222222-2222222222222222-01");

		try (var ignored = first.open()) {
			assertThat(MessageContext.current()).isEqualTo(first);
			assertThat(MDC.get("correlation_id")).isEqualTo("first");
			try (var nested = second.open()) {
				assertThat(MessageContext.current()).isEqualTo(second);
				assertThat(MDC.get("trace_id")).isEqualTo("22222222222222222222222222222222");
			}
			assertThat(MessageContext.current()).isEqualTo(first);
			assertThat(MDC.get("correlation_id")).isEqualTo("first");
		}

		assertThat(MDC.get("correlation_id")).isNull();
	}

	@Test
	void generatedTraceUsesW3cShapeAndNeverUsesZeroIds() {
		assertThat(MessageContext.newTrace()).matches("00-(?!0{32})[0-9a-f]{32}-(?!0{16})[0-9a-f]{16}-01");
	}

}
