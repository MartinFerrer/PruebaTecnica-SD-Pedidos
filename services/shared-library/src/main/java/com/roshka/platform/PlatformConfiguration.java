package com.roshka.platform;

import com.roshka.platform.json.JsonCodec;
import com.roshka.platform.messaging.BrokerTopology;
import com.roshka.platform.messaging.ConfirmedPublisher;
import com.roshka.platform.messaging.OutboxRelay;
import com.roshka.platform.web.CorrelationFilter;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Configuration
@Import({ BrokerTopology.class, ConfirmedPublisher.class, JsonCodec.class, OutboxRelay.class, CorrelationFilter.class })
public class PlatformConfiguration {

}
