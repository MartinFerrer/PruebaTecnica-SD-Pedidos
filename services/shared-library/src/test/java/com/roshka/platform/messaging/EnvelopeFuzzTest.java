package com.roshka.platform.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

import com.roshka.platform.json.JsonCodec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class EnvelopeFuzzTest {

	private static final int INPUTS = 512;

	private final JsonCodec json = new JsonCodec();

	@Test
	void corpusInputsRemainRejectedWithoutUnexpectedFailures() throws IOException {
		try (var files = Files.list(Path.of("src/test/resources/fuzz-corpus"))) {
			for (Path file : files.sorted().toList()) {
				assertRejected(Files.readString(file), file.toString());
			}
		}
	}

	@Test
	void seededInputsDoNotEscapeTheEnvelopeValidator() {
		long seed = Long.getLong("fuzz.seed", 20260911L);
		SplittableRandom random = new SplittableRandom(seed);
		for (int index = 0; index < INPUTS; index++) {
			String input = randomInput(random);
			try {
				JsonNode event = json.mapper.readTree(input);
				if (event != null && !event.isNull()) {
					EventContract.validate(event);
				}
			}
			catch (tools.jackson.core.JacksonException | IllegalArgumentException expected) {
				// Malformed input and contract rejection are expected fuzz outcomes.
			}
			catch (RuntimeException unexpected) {
				fail("seed=" + seed + ", index=" + index + ", input=" + input, unexpected);
			}
		}
	}

	private void assertRejected(String input, String source) {
		try {
			JsonNode event = json.mapper.readTree(input);
			if (event != null && !event.isNull()) {
				EventContract.validate(event);
			}
			else {
				fail("null envelope accepted: " + source);
			}
			fail("invalid envelope accepted: " + source);
		}
		catch (tools.jackson.core.JacksonException | IllegalArgumentException expected) {
			assertThat(expected).isNotNull();
		}
	}

	private String randomInput(SplittableRandom random) {
		String alphabet = "{}[],:\" abcdefghijklmnopqrstuvwxyz0123456789-";
		int length = random.nextInt(0, 160);
		StringBuilder value = new StringBuilder(length);
		for (int index = 0; index < length; index++) {
			value.append(alphabet.charAt(random.nextInt(alphabet.length())));
		}
		return value.toString();
	}

}
