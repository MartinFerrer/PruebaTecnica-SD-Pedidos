package com.roshka.order.adapter.out.persistence;

import com.roshka.order.application.port.out.OrderStore;
import com.roshka.order.domain.Order;
import jakarta.persistence.*;
import java.util.*;
import org.springframework.stereotype.Repository;

@Repository
public class JpaOrderStore implements OrderStore {
  @PersistenceContext private EntityManager em;
  private final OrderPersistenceMapper mapper;

  public JpaOrderStore(OrderPersistenceMapper mapper) {
    this.mapper = mapper;
  }

  public Optional<Order> find(UUID id, boolean lock) {
    OrderEntity entity =
        em.find(OrderEntity.class, id, lock ? LockModeType.PESSIMISTIC_WRITE : LockModeType.NONE);
    return entity == null ? Optional.empty() : Optional.of(mapper.toDomain(entity));
  }

  public List<Order> findAll() {
    return em.createQuery("select o from OrderEntity o order by o.orderId", OrderEntity.class).getResultList()
        .stream().map(mapper::toDomain).toList();
  }

  public void save(Order order) {
    OrderEntity entity = em.find(OrderEntity.class, order.orderId());
    boolean fresh = entity == null;
    if (fresh) {
      entity = new OrderEntity();
    }
    mapper.updateEntity(order, entity, fresh);
    if (fresh) em.persist(entity);
    em.flush();
  }
}
