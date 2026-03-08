package com.flowforge.api.service;

import java.util.Collections;
import java.util.Map;
import java.util.UUID;

/**
 * Thread-local holder for the current job id and params when executing in-process workers.
 * Set by {@link InProcessJobDispatcher} before invoking a worker runnable.
 */
public final class JobContextHolder {

    private static final ThreadLocal<UUID> JOB_ID = new ThreadLocal<>();
    private static final ThreadLocal<Map<String, Object>> PARAMS = new ThreadLocal<>();

    private JobContextHolder() {
    }

    public static void set(UUID jobId, Map<String, Object> params) {
        JOB_ID.set(jobId);
        PARAMS.set(params != null ? params : Map.of());
    }

    public static UUID getJobId() {
        return JOB_ID.get();
    }

    public static Map<String, Object> getParams() {
        Map<String, Object> p = PARAMS.get();
        return p != null ? p : Collections.emptyMap();
    }

    public static void clear() {
        JOB_ID.remove();
        PARAMS.remove();
    }
}
