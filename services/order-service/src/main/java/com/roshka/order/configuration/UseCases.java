package com.roshka.order.configuration;

import com.roshka.order.application.port.in.Orders;
import com.roshka.order.application.port.out.*;
import com.roshka.order.application.service.OrderService;
import java.util.UUID;
import org.springframework.context.annotation.*;

@Configuration
public class UseCases {
  @Bean
  Orders orders(OrderStore store, Events events) {
    return new OrderService(store, events, UUID::randomUUID);
  }
}
