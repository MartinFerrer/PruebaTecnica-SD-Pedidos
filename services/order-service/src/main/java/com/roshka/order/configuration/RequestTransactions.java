package com.roshka.order.configuration;

import com.roshka.order.adapter.out.persistence.JsonCodec;
import com.roshka.order.domain.BusinessException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class RequestTransactions {
  public record Reply(int status, String body) {}

  public record Result(int status, Object body) {}

  private final JdbcClient db;
  private final JsonCodec json;
  private final TransactionTemplate tx;

  public RequestTransactions(JdbcClient db, JsonCodec json, PlatformTransactionManager manager) {
    this.db = db;
    this.json = json;
    this.tx = new TransactionTemplate(manager);
    this.tx.setTimeout(15);
  }

  public <T> T read(Supplier<T> work) {
    return tx.execute(status -> work.get());
  }

  public Reply write(
      String operation, String key, Object request, int successStatus, Supplier<?> work) {
    if (key == null || key.isBlank() || key.length() > 128)
      return problem(400, "INVALID_IDEMPOTENCY_KEY");
    String fingerprint = digest(json.write(request));
    return tx.execute(
        status -> {
          db.sql("SET LOCAL lock_timeout = '3s'").update();
          int claimed =
              db.sql(
                      "INSERT INTO http_idempotency(operation, key, fingerprint) VALUES (:op,:key,:hash) ON CONFLICT DO NOTHING")
                  .param("op", operation).param("key", key).param("hash", fingerprint).update();
          if (claimed == 0) {
            return db.sql(
                    "SELECT fingerprint,status,body FROM http_idempotency WHERE operation=:op AND key=:key")
                .param("op", operation).param("key", key).query(
                    (rs, row) ->
                        fingerprint.equals(rs.getString(1))
                            ? new Reply(rs.getInt(2), rs.getString(3))
                            : problem(409, "IDEMPOTENCY_CONFLICT")).single();
          }
          Reply reply;
          try {
            Object result = work.get();
            reply =
                result instanceof Result r
                    ? new Reply(r.status(), json.write(r.body()))
                    : new Reply(successStatus, json.write(result));
          } catch (BusinessException e) {
            reply =
                problem(
                    switch (e.kind()) {
                      case INVALID -> 400;
                      case NOT_FOUND -> 404;
                      case CONFLICT -> 409;
                    },
                    e.getMessage());
          }
          db.sql(
                  "UPDATE http_idempotency SET status=:status,body=:body WHERE operation=:op AND key=:key")
              .param("status", reply.status()).param("body", reply.body()).param("op", operation).param("key", key)
              .update();
          return reply;
        });
  }

  public Reply problem(int status, String code) {
    return new Reply(
        status,
        json.write(Map.of("type", "about:blank", "title", code, "status", status, "code", code)));
  }

  private static String digest(String value) {
    try {
      return HexFormat.of().formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
