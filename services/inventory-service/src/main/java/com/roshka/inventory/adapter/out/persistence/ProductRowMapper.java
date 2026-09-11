package com.roshka.inventory.adapter.out.persistence;

import com.roshka.inventory.domain.Product;
import com.roshka.inventory.domain.Stock;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

@Component
class ProductRowMapper implements RowMapper<Product> {

	@Override
	public Product mapRow(ResultSet result, int rowNumber) throws SQLException {
		return new Product(result.getObject("product_id", UUID.class), result.getString("sku"),
				result.getString("name"),
				new Stock(result.getLong("on_hand"), result.getLong("reserved"), result.getLong("version")),
				result.getTimestamp("updated_at").toInstant());
	}

}
