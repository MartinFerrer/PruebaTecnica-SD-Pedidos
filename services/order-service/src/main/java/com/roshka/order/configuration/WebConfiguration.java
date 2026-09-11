package com.roshka.order.configuration;

import com.roshka.order.domain.BusinessException;
import com.roshka.platform.json.JsonCodec;
import com.roshka.platform.web.RequestTransactions;
import java.util.Optional;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
public class WebConfiguration {

	@Bean
	RequestTransactions requestTransactions(JdbcClient db, JsonCodec json,
			PlatformTransactionManager transactionManager) {
		return new RequestTransactions(db, json, transactionManager,
				failure -> failure instanceof BusinessException business
						? Optional.of(new RequestTransactions.Failure(status(business.kind()), business.code()))
						: Optional.empty());
	}

	private static int status(BusinessException.Kind kind) {
		return switch (kind) {
			case NOT_FOUND -> 404;
			case CONFLICT -> 409;
		};
	}

}
