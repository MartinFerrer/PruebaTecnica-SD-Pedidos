package com.roshka.order.application.port.out;

import java.util.UUID;

public interface EventPublisherPort {

	void publish(String type, UUID aggregateId, long version, Object payload);

}
