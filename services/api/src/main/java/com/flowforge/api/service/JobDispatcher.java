package com.flowforge.api.service;

import java.util.Map;
import java.util.UUID;

public interface JobDispatcher {
    void dispatch(String jobType, UUID jobId, Map<String, Object> params);
}
