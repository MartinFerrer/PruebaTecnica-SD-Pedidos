package com.roshka.order.configuration;

import com.roshka.order.application.port.out.EventPublisherPort;
import com.roshka.order.application.port.out.OrderStore;
import com.roshka.order.application.service.OrderService;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class UseCases {

	@Bean
	OrderService orderService(OrderStore store, EventPublisherPort events) {
		return new OrderService(store, events, UUID::randomUUID);
	}

}
