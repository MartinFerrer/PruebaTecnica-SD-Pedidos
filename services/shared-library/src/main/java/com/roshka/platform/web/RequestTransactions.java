package com.roshka.platform.web;

import com.roshka.platform.json.JsonCodec;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Transaction boundary for HTTP reads and idempotent commands.
 *
 * <p>
 * The business-failure mapper is supplied by each service, so this technical component
 * does not depend on either bounded context or on their exception hierarchies.
 */
public final class RequestTransactions {

	public record Result(int status, Object body) {
	}

	public record Failure(int status, String code) {
		public Failure {
			Objects.requireNonNull(code, "code");
		}
	}

	private final IdempotencyExecutor executor;

	private final Function<RuntimeException, Optional<Failure>> failureMapper;

	public RequestTransactions(JdbcClient db, JsonCodec json, PlatformTransactionManager transactionManager,
			Function<RuntimeException, Optional<Failure>> failureMapper) {
		this.executor = new IdempotencyExecutor(db, json, transactionManager);
		this.failureMapper = Objects.requireNonNull(failureMapper, "failureMapper");
	}

	public <T> T read(Supplier<T> work) {
		return executor.read(work);
	}

	public IdempotencyExecutor.Reply write(String operation, String key, Object request, int successStatus,
			Supplier<?> work) {
		return executor.write(operation, key, request, () -> {
			try {
				Object result = work.get();
				return result instanceof Result response ? executor.success(response.status(), response.body())
						: executor.success(successStatus, result);
			}
			catch (RuntimeException failure) {
				return failureMapper.apply(failure)
					.map(mapped -> executor.problem(mapped.status(), mapped.code()))
					.orElseThrow(() -> failure);
			}
		});
	}

	public IdempotencyExecutor.Reply problem(int status, String code) {
		return executor.problem(status, code);
	}

}
