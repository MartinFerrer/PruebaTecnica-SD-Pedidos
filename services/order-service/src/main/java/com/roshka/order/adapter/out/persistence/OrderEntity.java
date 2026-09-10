package com.roshka.order.adapter.out.persistence;

import jakarta.persistence.*;
import java.util.*;

@Entity
@Table(name = "orders")
class OrderEntity {
  @Id UUID orderId;

  @Column(nullable = false)
  String status;

  @Column(nullable = false)
  long version;

  String inventoryCancellationStatus;
  long cancellationVersion;

  @Column(nullable = false, columnDefinition = "text")
  String unavailableItems;

  @ElementCollection(fetch = FetchType.EAGER)
  @CollectionTable(name = "order_items", joinColumns = @JoinColumn(name = "order_id"))
  List<Line> items = new ArrayList<>();

  @Embeddable
  public static class Line {
    @Column(nullable = false)
    UUID productId;

    @Column(nullable = false)
    long quantity;

    public Line() {}

    Line(UUID id, long quantity) {
      this.productId = id;
      this.quantity = quantity;
    }
  }
}
