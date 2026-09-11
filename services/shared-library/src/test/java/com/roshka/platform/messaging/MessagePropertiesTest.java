package com.roshka.platform.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import net.jqwik.api.Example;

class MessagePropertiesTest {

	@Example
	void generatedTraceparentAlwaysUsesTheW3cShape() {
		assertThat(MessageContext.newTrace()).matches("00-[0-9a-f]{32}-[0-9a-f]{16}-01");
	}

}
