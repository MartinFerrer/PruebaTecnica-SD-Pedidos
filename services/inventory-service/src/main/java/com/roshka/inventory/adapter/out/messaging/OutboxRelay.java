package com.roshka.inventory.adapter.out.messaging;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.amqp.core.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class OutboxRelay {
  record Pending(UUID id, String type, String body, int attempts) {}

  private final JdbcClient db;
  private final ConfirmedPublisher publisher;
  private final TransactionTemplate tx;
  private final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(getClass());

  public OutboxRelay(
      JdbcClient db, ConfirmedPublisher publisher, PlatformTransactionManager manager) {
    this.db = db;
    this.publisher = publisher;
    tx = new TransactionTemplate(manager);
  }

  @Scheduled(fixedDelay = 250, initialDelay = 1000)
  public void dispatch() {
    UUID token = UUID.randomUUID();
    try {
      var pending =
          tx.execute(
              status ->
                  db.sql(
                          """
                UPDATE message_outbox SET lease_token=:token,lease_until=now()+interval '120 seconds'
                WHERE event_id IN (SELECT event_id FROM message_outbox
                    WHERE NOT published AND next_attempt<=now() AND (lease_until IS NULL OR lease_until<now())
                    ORDER BY created_at FOR UPDATE SKIP LOCKED LIMIT 20)
                RETURNING event_id,event_type,body,attempts
                """).param("token", token).query(
                          (rs, n) ->
                              new Pending(
                                  rs.getObject(1, UUID.class),
                                  rs.getString(2),
                                  rs.getString(3),
                                  rs.getInt(4))).list());
      for (var p : pending) {
        try {
          var properties = new MessageProperties();
          properties.setContentType("application/json");
          properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
          properties.setMessageId(p.id().toString());
          publisher.send(
              "business.events",
              p.type(),
              new Message(p.body().getBytes(StandardCharsets.UTF_8), properties));
          db.sql(
                  "UPDATE message_outbox SET published=true,lease_until=NULL WHERE event_id=:id AND lease_token=:token")
              .param("id", p.id()).param("token", token).update();
        } catch (RuntimeException e) {
          int delay =
              Math.min(60, 1 << Math.min(p.attempts(), 6)) + ThreadLocalRandom.current().nextInt(3);
          db.sql(
                  "UPDATE message_outbox SET attempts=attempts+1,lease_until=NULL,next_attempt=now()+(:delay * interval '1 second') WHERE event_id=:id AND lease_token=:token")
              .param("delay", delay).param("id", p.id()).param("token", token).update();
          log.warn("Outbox pending event={} reason={}", p.id(), e.toString());
        }
      }
    } catch (RuntimeException e) {
      log.warn("Outbox unavailable: {}", e.toString());
    }
  }
}
