package com.roshka.inventory.adapter.out.persistence;

import com.roshka.inventory.domain.Reservation;
import com.roshka.platform.json.JsonCodec;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
class ReservationPersistenceMapper {
  private final JsonCodec json;

  ReservationPersistenceMapper(JsonCodec json) {
    this.json = json;
  }

  Reservation toDomain(UUID orderId, ResultSet result) throws SQLException {
    return new Reservation(
        orderId,
        result.getString("state"),
        result.getLong("last_order_version"),
        result.getLong("version"),
        List.of(json.read(result.getString("items"), Reservation.Item[].class)));
  }

  String itemsToJson(Reservation reservation) {
    return json.write(reservation.items());
  }
}
