package com.roshka.order.adapter.out.persistence;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "orders")
class OrderEntity {

	@Id
	UUID orderId;

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

		public Line() {
		}

		Line(UUID id, long quantity) {
			this.productId = id;
			this.quantity = quantity;
		}

	}

}
