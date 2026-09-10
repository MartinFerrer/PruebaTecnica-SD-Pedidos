package com.roshka.inventory.configuration;

import com.roshka.inventory.application.port.in.Inventory;
import com.roshka.inventory.application.port.out.*;
import com.roshka.inventory.application.service.InventoryService;
import java.time.Clock;
import java.util.UUID;
import org.springframework.context.annotation.*;

@Configuration
public class UseCases {
  @Bean
  com.roshka.inventory.application.port.in.Reservations reservations(
      ReservationStore store, InventoryStore products, Events events) {
    return new com.roshka.inventory.application.service.ReservationService(
        store, products, events, Clock.systemUTC(), UUID::randomUUID);
  }

  @Bean
  Inventory inventory(InventoryStore store, Events events) {
    return new InventoryService(store, events, Clock.systemUTC(), UUID::randomUUID);
  }
}
