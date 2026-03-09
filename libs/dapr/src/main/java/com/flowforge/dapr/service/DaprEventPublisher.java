package com.flowforge.dapr.service;

import com.flowforge.dapr.config.DaprProperties;
import com.flowforge.dapr.events.PipelineEvent;
import io.dapr.client.DaprClient;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class DaprEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(DaprEventPublisher.class);

    private final DaprClient daprClient;
    private final DaprProperties props;
    private final MeterRegistry meterRegistry;

    public DaprEventPublisher(DaprClient daprClient, DaprProperties props, MeterRegistry meterRegistry) {
        this.daprClient = daprClient;
        this.props = props;
        this.meterRegistry = meterRegistry;
    }

    public void publish(String topic, PipelineEvent event) {
        try {
            daprClient.publishEvent(
                    props.pubsubName(),
                    topic,
                    event,
                    Map.of(
                        "content-type", "application/json",
                        "cloudevent.type", event.type(),
                        "cloudevent.source", "flowforge/" + event.stage()
                    )
                )
                .block(props.timeout());

            meterRegistry.counter("flowforge.dapr.pubsub.published", "topic", topic).increment();
            log.debug("Published event to {}: {}", topic, event.type());
        } catch (Exception e) {
            meterRegistry.counter("flowforge.dapr.pubsub.error", "topic", topic).increment();
            log.error("Failed to publish event to {}", topic, e);
            throw new DaprPublishException(topic, e);
        }
    }
}

class DaprPublishException extends RuntimeException {
    DaprPublishException(String topic, Throwable cause) {
        super("Failed to publish Dapr event to topic " + topic, cause);
    }
}


