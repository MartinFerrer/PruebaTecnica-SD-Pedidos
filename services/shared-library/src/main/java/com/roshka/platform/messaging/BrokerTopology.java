package com.roshka.platform.messaging;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
public class BrokerTopology {

	@Bean
	public Declarables topology() {
		List<Declarable> declarations = new ArrayList<>();
		TopicExchange exchange = new TopicExchange("business.events", true, false);
		declarations.add(exchange);
		for (String service : List.of("order", "inventory")) {
			var args = new HashMap<String, Object>();
			args.put("x-queue-type", "quorum");
			args.put("x-overflow", "reject-publish");
			args.put("x-delivery-limit", 20);
			args.put("x-dead-letter-exchange", "");
			args.put("x-dead-letter-routing-key", service + ".dlq");
			args.put("x-dead-letter-strategy", "at-least-once");
			Queue queue = new Queue(service + ".in", true, false, false, args);
			declarations.add(queue);
			declarations.add(QueueBuilder.durable(service + ".dlq").quorum().build());
			for (int delay : List.of(1, 5, 30)) {
				Map<String, Object> retry = new HashMap<>(args);
				retry.put("x-message-ttl", delay * 1000);
				retry.put("x-dead-letter-routing-key", service + ".in");
				declarations.add(new Queue(service + ".retry." + delay, true, false, false, retry));
			}
			List<String> types = service.equals("order") ? List.of("StockReserved", "StockRejected", "StockReleased")
														 : List.of("OrderCreated", "OrderCancelled");
			for (String type : types) {
				declarations.add(BindingBuilder.bind(queue).to(exchange).with(type));
			}
		}
		Queue audit = QueueBuilder.durable("inventory.products").quorum().ttl(86400000).maxLength(10000).build();
		declarations.add(audit);
		declarations.add(BindingBuilder.bind(audit).to(exchange).with("ProductStockCreated"));
		declarations.add(BindingBuilder.bind(audit).to(exchange).with("ProductStockReplenished"));
		declarations.add(BindingBuilder.bind(audit).to(exchange).with("ProductStockUpdated"));
		return new Declarables(declarations);
	}

}
