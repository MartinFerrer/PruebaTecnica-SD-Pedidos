package com.roshka.testing;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * Test-only consistency checks. It deliberately uses SQL snapshots instead of
 * production repositories so a test cannot pass by repeating the implementation.
 */
public final class InvariantVerifier {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private InvariantVerifier() {
	}

	public record InventorySnapshot(long onHand, long reserved, long effectiveOnHand, long effectiveReserved,
			Map<UUID, Long> productReserved, Map<UUID, Long> activeReserved) {
	}

	public static InventorySnapshot inventory(JdbcClient db) {
		long[] products = db.sql("SELECT COALESCE(SUM(on_hand),0), COALESCE(SUM(reserved),0) FROM products")
			.query((rs, row) -> new long[] { rs.getLong(1), rs.getLong(2) }).single();
		long[] movements = db.sql(
				"SELECT COALESCE(SUM(delta_hand),0), COALESCE(SUM(delta_reserved),0) FROM stock_movements")
			.query((rs, row) -> new long[] { rs.getLong(1), rs.getLong(2) }).single();
		Map<UUID, Long> productReserved = db.sql("SELECT product_id,reserved FROM products")
			.query((rs, row) -> Map.entry(rs.getObject(1, UUID.class), rs.getLong(2))).list().stream()
			.filter(entry -> entry.getValue() > 0)
			.collect(java.util.stream.Collectors.toMap(entry -> entry.getKey(), entry -> entry.getValue()));
		Map<UUID, Long> activeReserved = new LinkedHashMap<>();
		db.sql("SELECT items FROM reservations WHERE state='RESERVED'").query(String.class).list().forEach(items -> {
			try {
				for (var item : JSON.readTree(items)) {
					UUID product = UUID.fromString(item.path("productId").asString());
					activeReserved.merge(product, item.path("quantity").asLong(), (left, right) -> left + right);
				}
			}
			catch (Exception exception) {
				throw new AssertionError("Invalid active reservation JSON", exception);
			}
		});
		return new InventorySnapshot(products[0], products[1], movements[0], movements[1], productReserved,
				activeReserved);
	}

	public static void assertInventory(JdbcClient db) {
		InventorySnapshot snapshot = inventory(db);
		require(snapshot.onHand() >= snapshot.reserved(), "onHand < reserved");
		require(snapshot.reserved() >= 0, "reserved < 0");
		require(snapshot.effectiveOnHand() == snapshot.onHand(),
				"movement history does not explain onHand");
		require(snapshot.effectiveReserved() == snapshot.reserved(),
				"movement history does not explain reserved");
		require(snapshot.productReserved().equals(snapshot.activeReserved()),
				"active reservations do not match product.reserved: " + snapshot);
	}

	public static void assertOrderReservations(JdbcClient orderDb, JdbcClient inventoryDb) {
		orderDb.sql("SELECT order_id,status FROM orders").query((rs, row) -> Map.entry(
				rs.getObject(1, UUID.class), rs.getString(2))).list().forEach(order -> {
			String reservation = inventoryDb.sql("SELECT state FROM reservations WHERE order_id=:id")
				.param("id", order.getKey()).query(String.class).optional().orElse(null);
			if ("CONFIRMED".equals(order.getValue())) {
				require("RESERVED".equals(reservation), "confirmed order without active reservation: " + order.getKey());
			}
			else if ("REJECTED".equals(order.getValue())) {
				require("REJECTED".equals(reservation), "rejected order without rejected reservation: " + order.getKey());
			}
			else if ("CANCELLED".equals(order.getValue())) {
				require("RELEASED".equals(reservation) || "CANCELLED_BEFORE_RESERVATION".equals(reservation),
						"cancelled order did not converge: " + order.getKey());
			}
		});
	}

	private static void require(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}

}
