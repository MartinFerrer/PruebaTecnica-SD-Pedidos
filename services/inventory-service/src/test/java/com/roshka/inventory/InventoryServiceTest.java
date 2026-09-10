package com.roshka.inventory;

import static org.assertj.core.api.Assertions.*;

import com.roshka.inventory.application.port.in.Inventory;
import com.roshka.inventory.application.port.out.InventoryStore;
import com.roshka.inventory.application.service.InventoryService;
import com.roshka.inventory.domain.*;
import java.time.Clock;
import java.util.*;
import org.junit.jupiter.api.Test;

class InventoryServiceTest {
  final Map<UUID, Product> products = new HashMap<>();
  final Map<UUID, Inventory.Change> movements = new HashMap<>();
  final List<String> events = new ArrayList<>();
  final InventoryStore store =
      new InventoryStore() {
        public void lockIdentity(String id) {}

        public boolean skuExists(String sku) {
          return products.values().stream().anyMatch(p -> p.sku().equals(sku));
        }

        public Optional<Product> find(UUID id, boolean lock) {
          return Optional.ofNullable(products.get(id));
        }

        public List<Product> findAll() {
          return new ArrayList<>(products.values());
        }

        public void insert(Product p) {
          products.put(p.productId(), p);
        }

        public void update(Product p) {
          products.put(p.productId(), p);
        }

        public Optional<Inventory.Change> movement(UUID id) {
          return Optional.ofNullable(movements.get(id));
        }

        public void movement(String op, Product before, Product after, Inventory.Change result) {
          movements.put(result.movementId(), result);
        }
      };
  final InventoryService service =
      new InventoryService(
          store,
          (type, id, version, payload) -> events.add(type),
          Clock.systemUTC(),
          UUID::randomUUID);

  @Test
  void restockIdentityAndRecountProtectStock() {
    var p = service.create(new Inventory.Create("SKU", "Test", 20));
    UUID movement = UUID.randomUUID();
    var command = new Inventory.Restock(movement, 5, "DELIVERY");
    var result = service.restock(p.productId(), command);
    assertThat(service.restock(p.productId(), command)).isEqualTo(result);
    for (var mismatch :
        List.of(
            new Inventory.Restock(movement, 6, "DELIVERY"),
            new Inventory.Restock(movement, 5, "OTHER"))) {
      assertThatThrownBy(() -> service.restock(p.productId(), mismatch)).isInstanceOf(BusinessException.class);
    }
    assertThatThrownBy(() -> service.restock(UUID.randomUUID(), command)).isInstanceOf(BusinessException.class);
    assertThatThrownBy(() -> service.recount(new Inventory.Recount(p.productId(), 20, 1, "COUNT")))
        .isInstanceOf(BusinessException.class);
    service.recount(new Inventory.Recount(p.productId(), 30, 2, "COUNT"));
    service.recount(new Inventory.Recount(p.productId(), 30, 3, "COUNT"));
    assertThat(service.get(p.productId()).onHand()).isEqualTo(30);
    assertThat(events).containsExactly("ProductStockCreated", "ProductStockReplenished", "ProductStockUpdated");
  }

  @Test
  void duplicateSkuMissingProductAndOverflowAreRejected() {
    var product = service.create(new Inventory.Create("SKU", "Test", 1000000000));
    assertThatThrownBy(() -> service.create(new Inventory.Create("SKU", "Another", 0)))
        .isInstanceOf(BusinessException.class);
    assertThatThrownBy(() -> service.get(UUID.randomUUID())).isInstanceOf(BusinessException.class);
    assertThatThrownBy(
            () ->
                service.restock(
                    product.productId(), new Inventory.Restock(UUID.randomUUID(), 1, "TEST")))
        .isInstanceOf(BusinessException.class);
  }
}
