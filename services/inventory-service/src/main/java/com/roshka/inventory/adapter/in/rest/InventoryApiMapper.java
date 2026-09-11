package com.roshka.inventory.adapter.in.rest;

import com.roshka.inventory.application.model.ProductSnapshot;
import com.roshka.inventory.application.model.StockChange;
import com.roshka.inventory.application.port.in.CreateProductUseCase;
import com.roshka.inventory.application.port.in.RecountStockUseCase;
import com.roshka.inventory.application.port.in.RestockProductUseCase;
import org.springframework.stereotype.Component;

@Component
public class InventoryApiMapper {

	public CreateProductUseCase.Command toCommand(InventoryRequest.Create request) {
		return new CreateProductUseCase.Command(request.sku(), request.name(), request.initialStock());
	}

	public RestockProductUseCase.Command toCommand(InventoryRequest.Restock request) {
		return new RestockProductUseCase.Command(request.movementId(), request.quantity(), request.reason());
	}

	public RecountStockUseCase.Command toCommand(InventoryRequest.Recount request) {
		return new RecountStockUseCase.Command(request.productId(), request.stock(), request.expectedVersion(),
				request.reason());
	}

	public ProductResponse toProductResponse(ProductSnapshot product) {
		return new ProductResponse(product.productId(), product.sku(), product.name(), product.onHand(),
				product.reserved(), product.available(), product.version(), product.updatedAt());
	}

	public StockResponse toStockResponse(ProductSnapshot product) {
		return new StockResponse(product.productId(), product.onHand(), product.reserved(), product.available(),
				product.version(), product.updatedAt());
	}

	public StockChangeResponse toResponse(StockChange change) {
		return new StockChangeResponse(change.productId(), change.movementId(), change.quantity(),
				change.previousOnHand(), change.onHand(), change.reserved(), change.available(), change.version(),
				change.reason());
	}

}
