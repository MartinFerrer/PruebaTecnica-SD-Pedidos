package com.roshka.platform.web;

import com.roshka.platform.json.JsonCodec;
import com.roshka.platform.messaging.MessageContext;
import com.roshka.platform.observability.PlatformMetrics;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.function.Supplier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Executes HTTP commands with a durable idempotency record in the service-owned database.
 *
 * <p>
 * The executor is deliberately unaware of domain exceptions. Callers turn an expected
 * domain failure into a {@link Reply} inside the callback, while unexpected failures roll
 * back the transaction and remain exceptional.
 */
public final class IdempotencyExecutor {

	public record Reply(int status, String body, Map<String, String> headers) {
		public Reply {
			headers = Map.copyOf(headers);
		}

		public Reply(int status, String body) {
			this(status, body, defaultHeaders(status));
		}
	}

	private final JdbcClient db;

	private final JsonCodec json;

	private final TransactionTemplate tx;

	private final PlatformMetrics metrics;

	public IdempotencyExecutor(JdbcClient db, JsonCodec json, PlatformTransactionManager manager) {
		this(db, json, manager, PlatformMetrics.noop());
	}

	public IdempotencyExecutor(JdbcClient db, JsonCodec json, PlatformTransactionManager manager,
			PlatformMetrics metrics) {
		this.db = db;
		this.json = json;
		this.tx = new TransactionTemplate(manager);
		this.metrics = metrics;
		this.tx.setTimeout(15);
	}

	public <T> T read(Supplier<T> work) {
		return tx.execute(status -> work.get());
	}

	public Reply write(String operation, String key, Object request, Supplier<Reply> work) {
		if (key == null || key.isBlank() || key.length() > 128) {
			return problem(400, "INVALID_IDEMPOTENCY_KEY");
		}
		String fingerprint = fingerprint(request);
		for (int attempt = 1; ; attempt++) {
			try {
				return executeWrite(operation, key, fingerprint, work);
			}
			catch (org.springframework.dao.DataAccessException failure) {
				if (attempt >= 3 || !transactionAborted(failure)) {
					throw failure;
				}
			}
		}
	}

	private static boolean transactionAborted(Throwable failure) {
		for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
			if (cause instanceof java.sql.SQLException sql
					&& ("40P01".equals(sql.getSQLState()) || "40001".equals(sql.getSQLState()))) {
				return true;
			}
		}
		return false;
	}

	private Reply executeWrite(String operation, String key, String fingerprint, Supplier<Reply> work) {
		return tx.execute(status -> {
			db.sql("SET LOCAL lock_timeout = '3s'").update();
			int claimed = claim(operation, key, fingerprint);
			if (claimed == 0) {
				metrics.increment("idempotency_replays_total", "operation", operation);
				return existing(operation, key, fingerprint);
			}

			Reply reply = Objects.requireNonNull(work.get(), "idempotency callback result");
			db.sql("UPDATE http_idempotency SET status=:status,body=:body,headers=CAST(:headers AS jsonb) "
					+ "WHERE operation=:op AND key=:key")
				.param("status", reply.status())
				.param("body", reply.body())
				.param("headers", json.write(reply.headers()))
				.param("op", operation)
				.param("key", key)
				.update();
			return reply;
		});
	}

	public Reply success(int status, Object body) {
		return new Reply(status, json.write(body));
	}

	public Reply success(int status, Object body, Map<String, String> additionalHeaders) {
		var headers = new LinkedHashMap<>(defaultHeaders(status));
		headers.putAll(additionalHeaders);
		return new Reply(status, json.write(body), headers);
	}

	private static Map<String, String> defaultHeaders(int status) {
		var headers = new LinkedHashMap<String, String>();
		headers.put("Content-Type", status >= 400 ? "application/problem+json" : "application/json");
		headers.put("X-Correlation-Id", MessageContext.current().correlationId());
		if (status == 503) {
			headers.put("Retry-After", "1");
		}
		return headers;
	}

	public Reply problem(int status, String code) {
		return new Reply(status, json.write(ErrorResponse.of(status, code)));
	}

	private int claim(String operation, String key, String fingerprint) {
		return db
			.sql("INSERT INTO http_idempotency(operation, key, fingerprint) "
					+ "VALUES (:op,:key,:hash) ON CONFLICT DO NOTHING")
			.param("op", operation)
			.param("key", key)
			.param("hash", fingerprint)
			.update();
	}

	private Reply existing(String operation, String key, String fingerprint) {
		return db.sql("SELECT fingerprint,status,body,headers FROM http_idempotency WHERE operation=:op AND key=:key")
			.param("op", operation)
			.param("key", key)
			.query((rs, row) -> fingerprint.equals(rs.getString(1))
					? new Reply(rs.getInt(2), rs.getString(3), json.mapper.readValue(rs.getString(4),
							new tools.jackson.core.type.TypeReference<Map<String, String>>() { }))
					: problem(409, "IDEMPOTENCY_CONFLICT"))
			.single();
	}

	private String fingerprint(Object request) {
		return digest(json.write(request));
	}

	private static String digest(String value) {
		try {
			return HexFormat.of()
				.formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
		}
		catch (java.security.NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 is required by the runtime", e);
		}
	}

}
