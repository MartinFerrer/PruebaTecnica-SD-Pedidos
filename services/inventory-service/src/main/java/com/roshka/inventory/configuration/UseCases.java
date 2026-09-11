package com.roshka.inventory.configuration;

import com.roshka.inventory.application.port.out.EventPublisherPort;
import com.roshka.inventory.application.port.out.InventoryStore;
import com.roshka.inventory.application.port.out.ReservationStore;
import com.roshka.inventory.application.service.InventoryService;
import com.roshka.inventory.application.service.ReservationService;
import java.time.Clock;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class UseCases {

	@Bean
	ReservationService reservationService(ReservationStore store, InventoryStore products, EventPublisherPort events) {
		return new ReservationService(store, products, events, Clock.systemUTC(), UUID::randomUUID);
	}

	@Bean
	InventoryService inventoryService(InventoryStore store, EventPublisherPort events) {
		return new InventoryService(store, events, Clock.systemUTC(), UUID::randomUUID);
	}

}
