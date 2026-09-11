package com.roshka.inventory.application.port.in;

import com.roshka.inventory.application.model.StockChange;
import java.util.UUID;

public interface RestockProductUseCase {

	record Command(UUID movementId, long quantity, String reason) {
	}

	StockChange restock(UUID productId, Command command);

}
