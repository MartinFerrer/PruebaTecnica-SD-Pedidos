package com.roshka.inventory.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.LongRange;

class SeededScenarioPropertiesTest {

	@Property(tries = 40, seed = "20260911")
	void seededHistoryCanBeReplayedWithoutBreakingStockInvariants(
			@ForAll @LongRange(min = 1, max = Long.MAX_VALUE) long seed) {
		List<Operation> history = generate(seed, 48);
		Stock replayed = replay(history);

		assertThat(replayed.onHand()).isGreaterThanOrEqualTo(replayed.reserved());
		assertThat(replayed.reserved()).isGreaterThanOrEqualTo(0);
	}

	private List<Operation> generate(long seed, int size) {
		SplittableRandom random = new SplittableRandom(seed);
		List<Operation> operations = new ArrayList<>();
		for (int index = 0; index < size; index++) {
			operations.add(new Operation(random.nextInt(3), random.nextLong(1, 6)));
		}
		return List.copyOf(operations);
	}

	private Stock replay(List<Operation> history) {
		Stock stock = new Stock(10, 0, 1);
		for (Operation operation : history) {
			try {
				stock = switch (operation.kind()) {
					case 0 -> stock.restock(operation.quantity());
					case 1 -> stock.reserve(operation.quantity());
					default -> stock.release(operation.quantity());
				};
			}
			catch (IllegalArgumentException ignored) {
				// Invalid transitions are rejected and do not alter the snapshot.
			}
			assertThat(stock.available()).as("history=%s reduced=%s", history, reduce(history))
					.isGreaterThanOrEqualTo(0);
		}
		return stock;
	}

	private List<Operation> reduce(List<Operation> history) {
		int from = Math.max(0, history.size() - 8);
		return history.subList(from, history.size());
	}

	private record Operation(int kind, long quantity) {
	}

}
