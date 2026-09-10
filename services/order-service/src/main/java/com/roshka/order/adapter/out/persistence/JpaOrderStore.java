package com.roshka.order.adapter.out.persistence;

import com.roshka.order.application.port.out.OrderStore;
import com.roshka.order.domain.*;
import jakarta.persistence.*;
import java.util.*;
import org.springframework.stereotype.Repository;

@Repository
public class JpaOrderStore implements OrderStore {
  @PersistenceContext private EntityManager em;
  private final JsonCodec json;

  public JpaOrderStore(JsonCodec json) {
    this.json = json;
  }

  public Optional<Order> find(UUID id, boolean lock) {
    OrderEntity entity =
        em.find(OrderEntity.class, id, lock ? LockModeType.PESSIMISTIC_WRITE : LockModeType.NONE);
    return entity == null
        ? Optional.empty()
        : Optional.of(
            new Order(
                entity.orderId,
                entity.items.stream().map(i -> new Order.Item(i.productId, i.quantity)).toList(),
                OrderStatus.valueOf(entity.status),
                entity.version,
                entity.inventoryCancellationStatus,
                entity.cancellationVersion,
                List.of(json.read(entity.unavailableItems, Order.Shortage[].class))));
  }

  public List<Order> findAll() {
    return em.createQuery("select o from OrderEntity o order by o.orderId", OrderEntity.class).getResultList()
        .stream().map(this::toDomain).toList();
  }

  private Order toDomain(OrderEntity entity) {
    return new Order(
        entity.orderId,
        entity.items.stream().map(i -> new Order.Item(i.productId, i.quantity)).toList(),
        OrderStatus.valueOf(entity.status),
        entity.version,
        entity.inventoryCancellationStatus,
        entity.cancellationVersion,
        List.of(json.read(entity.unavailableItems, Order.Shortage[].class)));
  }

  public void save(Order order) {
    OrderEntity entity = em.find(OrderEntity.class, order.orderId());
    boolean fresh = entity == null;
    if (fresh) {
      entity = new OrderEntity();
      entity.orderId = order.orderId();
      entity.items =
          new ArrayList<>(
              order.items().stream().map(i -> new OrderEntity.Line(i.productId(), i.quantity())).toList());
    }
    entity.status = order.status().name();
    entity.version = order.version();
    entity.inventoryCancellationStatus = order.inventoryCancellationStatus();
    entity.cancellationVersion = order.cancellationVersion();
    entity.unavailableItems = json.write(order.unavailableItems());
    if (fresh) em.persist(entity);
    em.flush();
  }
}
