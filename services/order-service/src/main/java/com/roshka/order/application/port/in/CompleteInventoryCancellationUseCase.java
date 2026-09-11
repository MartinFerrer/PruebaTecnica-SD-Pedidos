package com.roshka.order.application.port.in;

import java.util.List;
import java.util.UUID;

public interface CompleteInventoryCancellationUseCase {

	record Item(UUID productId, long quantity) {
	}

	record Result(UUID orderId, long requestOrderVersion, String outcome, List<Item> releasedItems) {
		public Result {
			releasedItems = List.copyOf(releasedItems);
		}
	}

	void complete(Result result);

}
