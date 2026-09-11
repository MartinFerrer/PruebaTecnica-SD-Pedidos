package com.roshka.order.application.port.in;

import java.util.List;
import java.util.UUID;

public interface ApplyReservationResultUseCase {

	sealed interface Result permits Reserved, Rejected {

		UUID orderId();

		long requestOrderVersion();

	}

	record Item(UUID productId, long quantity) {
	}

	record Shortage(UUID productId, long requested, long available, String reason) {
	}

	record Reserved(UUID orderId, long requestOrderVersion, List<Item> items) implements Result {
		public Reserved {
			items = List.copyOf(items);
		}
	}

	record Rejected(UUID orderId, long requestOrderVersion, List<Shortage> shortages) implements Result {
		public Rejected {
			shortages = List.copyOf(shortages);
		}
	}

	void apply(Result result);

}
