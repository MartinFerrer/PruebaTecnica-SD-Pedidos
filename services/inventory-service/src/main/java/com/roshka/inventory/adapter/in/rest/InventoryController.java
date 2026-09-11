package com.roshka.inventory.adapter.in.rest;

import com.roshka.inventory.application.port.in.CreateProductUseCase;
import com.roshka.inventory.application.port.in.FindInventoryQuery;
import com.roshka.inventory.application.port.in.RecountStockUseCase;
import com.roshka.inventory.application.port.in.RestockProductUseCase;
import com.roshka.platform.web.HttpResponseMapper;
import com.roshka.platform.web.RequestTransactions;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class InventoryController {

	private final CreateProductUseCase createProduct;

	private final FindInventoryQuery findInventory;

	private final RestockProductUseCase restockProduct;

	private final RecountStockUseCase recountStock;

	private final RequestTransactions transactions;

	private final InventoryApiMapper mapper;

	public InventoryController(CreateProductUseCase createProduct, FindInventoryQuery findInventory,
			RestockProductUseCase restockProduct, RecountStockUseCase recountStock, RequestTransactions transactions,
			InventoryApiMapper mapper) {
		this.createProduct = createProduct;
		this.findInventory = findInventory;
		this.restockProduct = restockProduct;
		this.recountStock = recountStock;
		this.transactions = transactions;
		this.mapper = mapper;
	}

	@PostMapping("/products")
	ResponseEntity<String> create(@RequestHeader(value = "Idempotency-Key", required = false) String key,
			@Valid @RequestBody InventoryRequest.Create request) {
		return HttpResponseMapper.toResponse(transactions.write("create-product", key, request, 201,
				() -> mapper.toProductResponse(createProduct.create(mapper.toCommand(request)))));
	}

	@GetMapping("/products/{id}/stock")
	StockResponse get(@PathVariable UUID id) {
		return transactions.read(() -> mapper.toStockResponse(findInventory.findById(id)));
	}

	@GetMapping("/products")
	List<ProductResponse> list() {
		return transactions.read(() -> findInventory.findAll().stream().map(mapper::toProductResponse).toList());
	}

	@PostMapping("/products/{id}/restock")
	ResponseEntity<String> restock(@PathVariable UUID id,
			@RequestHeader(value = "Idempotency-Key", required = false) String key,
			@Valid @RequestBody InventoryRequest.Restock request) {
		return HttpResponseMapper
			.toResponse(transactions.write("restock-product", key, Map.of("productId", id, "body", request), 201,
					() -> mapper.toResponse(restockProduct.restock(id, mapper.toCommand(request)))));
	}

	@PutMapping("/products")
	ResponseEntity<String> recount(@RequestHeader(value = "Idempotency-Key", required = false) String key,
			@Valid @RequestBody InventoryRequest.Recount request) {
		return HttpResponseMapper.toResponse(transactions.write("recount-product", key, request, 200,
				() -> mapper.toResponse(recountStock.recount(mapper.toCommand(request)))));
	}

}
