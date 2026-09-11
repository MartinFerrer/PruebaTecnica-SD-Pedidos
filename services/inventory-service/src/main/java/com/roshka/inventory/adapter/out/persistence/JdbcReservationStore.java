package com.roshka.inventory.adapter.out.persistence;

import com.roshka.inventory.application.port.out.ReservationStore;
import com.roshka.inventory.domain.Reservation;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcReservationStore implements ReservationStore {
  private final JdbcClient db;
  private final ReservationPersistenceMapper mapper;

  public JdbcReservationStore(JdbcClient db, ReservationPersistenceMapper mapper) {
    this.db = db;
    this.mapper = mapper;
  }

  public void lock(UUID orderId) {
    db.sql("SELECT pg_advisory_xact_lock(hashtextextended(:id,0))").param("id", "order:" + orderId).query(rs -> {});
  }

  public Optional<Reservation> find(UUID id) {
    return db.sql("SELECT * FROM reservations WHERE order_id=:id")
        .param("id", id)
        .query((rs, rowNumber) -> mapper.toDomain(id, rs))
        .optional();
  }

  public void save(Reservation r) {
    db.sql(
            "INSERT INTO reservations(order_id,state,last_order_version,version,items) VALUES (:id,:state,:last,:version,:items) ON CONFLICT(order_id) DO UPDATE SET state=EXCLUDED.state,last_order_version=EXCLUDED.last_order_version,version=EXCLUDED.version,items=EXCLUDED.items")
        .param("id", r.orderId()).param("state", r.state()).param("last", r.lastOrderVersion())
        .param("version", r.version()).param("items", mapper.itemsToJson(r)).update();
  }
}
