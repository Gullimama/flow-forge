package com.flowforge.api.service;

import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "flowforge.dispatch.mode", havingValue = "stub", matchIfMissing = true)
public class StubJobDispatcher implements JobDispatcher {

    private static final Logger log = LoggerFactory.getLogger(StubJobDispatcher.class);

    @Override
    public void dispatch(String jobType, UUID jobId, Map<String, Object> params) {
        log.info("STUB: Would dispatch job {} of type {} with params {}", jobId, jobType, params);
    }
}
