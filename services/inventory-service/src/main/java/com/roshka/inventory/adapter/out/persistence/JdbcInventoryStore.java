package com.roshka.inventory.adapter.out.persistence;

import com.roshka.inventory.application.model.StockChange;
import com.roshka.inventory.application.port.out.InventoryStore;
import com.roshka.inventory.domain.*;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcInventoryStore implements InventoryStore {
  private final JdbcClient db;
  private final ProductRowMapper products;
  private final StockMovementPersistenceMapper movements;

  public JdbcInventoryStore(
      JdbcClient db,
      ProductRowMapper products,
      StockMovementPersistenceMapper movements) {
    this.db = db;
    this.products = products;
    this.movements = movements;
  }

  public void lockIdentity(String value) {
    db.sql("SELECT pg_advisory_xact_lock(hashtextextended(:value,0))").param("value", value).query(rs -> {});
  }

  public boolean skuExists(String sku) {
    return db.sql("SELECT count(*) FROM products WHERE sku=:sku").param("sku", sku).query(Long.class).single()
        > 0;
  }

  public Optional<Product> find(UUID id, boolean lock) {
    return db.sql("SELECT * FROM products WHERE product_id=:id" + (lock ? " FOR UPDATE" : ""))
        .param("id", id)
        .query(products)
        .optional();
  }

  public List<Product> findAll() {
    return db.sql("SELECT * FROM products ORDER BY product_id")
        .query(products)
        .list();
  }

  public void insert(Product p) {
    db.sql(
            "INSERT INTO products(product_id,sku,name,on_hand,reserved,version,updated_at) VALUES (:id,:sku,:name,:hand,0,1,:at)")
        .param("id", p.productId()).param("sku", p.sku()).param("name", p.name()).param("hand", p.stock().onHand())
        .param("at", java.sql.Timestamp.from(p.updatedAt())).update();
  }

  public void update(Product p) {
    db.sql(
            "UPDATE products SET on_hand=:hand,reserved=:reserved,version=:version,updated_at=:at WHERE product_id=:id")
        .param("hand", p.stock().onHand()).param("reserved", p.stock().reserved()).param("version", p.stock().version())
        .param("at", java.sql.Timestamp.from(p.updatedAt())).param("id", p.productId()).update();
  }

  public Optional<StockChange> movement(UUID id) {
    return db.sql("SELECT response FROM stock_movements WHERE movement_id=:id").param("id", id)
        .query((rs, n) -> movements.fromJson(rs.getString(1))).optional();
  }

  public void movement(
      String operation, Product before, Product after, StockChange result) {
    db.sql(
            "INSERT INTO stock_movements(movement_id,product_id,operation,delta_hand,delta_reserved,version,response) VALUES (:id,:product,:op,:hand,:reserved,:version,:response)")
        .param("id", result.movementId()).param("product", after.productId()).param("op", operation)
        .param("hand", after.stock().onHand() - (before == null ? 0 : before.stock().onHand())).param(
            "reserved", after.stock().reserved() - (before == null ? 0 : before.stock().reserved()))
        .param("version", after.stock().version()).param("response", movements.toJson(result)).update();
  }
}
