package com.roshka.inventory.application.port.in;

import com.roshka.inventory.application.model.ProductSnapshot;
import java.util.List;
import java.util.UUID;

public interface FindInventoryQuery {

	ProductSnapshot findById(UUID productId);

	List<ProductSnapshot> findAll();

}
