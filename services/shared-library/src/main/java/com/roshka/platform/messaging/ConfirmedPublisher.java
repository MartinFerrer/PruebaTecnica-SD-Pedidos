package com.roshka.platform.messaging;

import java.util.concurrent.TimeUnit;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

@Component
public class ConfirmedPublisher {
  private final RabbitTemplate rabbit;

  public ConfirmedPublisher(RabbitTemplate rabbit) {
    this.rabbit = rabbit;
  }

  public void send(String exchange, String routingKey, Message message) {
    CorrelationData correlation = new CorrelationData();
    rabbit.send(exchange, routingKey, message, correlation);
    try {
      var confirm = correlation.getFuture().get(5, TimeUnit.SECONDS);
      if (!confirm.ack() || correlation.getReturned() != null) {
        throw new IllegalStateException("PUBLISH_NOT_CONFIRMED_OR_RETURNED");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("PUBLISH_INTERRUPTED", e);
    } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
      throw new IllegalStateException("PUBLISH_UNCERTAIN", e);
    }
  }
}
