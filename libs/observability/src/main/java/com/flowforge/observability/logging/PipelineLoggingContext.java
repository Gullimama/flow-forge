package com.flowforge.observability.logging;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

@Component
public class PipelineLoggingContext {

    private static final ScopedValue<Map<String, String>> PIPELINE_CONTEXT = ScopedValue.newInstance();

    /**
     * Execute a block with pipeline context that propagates correctly to child virtual threads via ScopedValue.
     */
    public <T> T withContext(UUID snapshotId, String stageName, Supplier<T> block) {
        Map<String, String> contextMap = Map.of(
            "snapshotId", snapshotId.toString(),
            "stage", stageName
        );
        try {
            return ScopedValue.where(PIPELINE_CONTEXT, contextMap).call(() -> {
                MDC.put("snapshotId", snapshotId.toString());
                MDC.put("stage", stageName);
                try {
                    return block.get();
                } finally {
                    MDC.remove("snapshotId");
                    MDC.remove("stage");
                }
            });
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Restore MDC from ScopedValue context. Call this at the start of virtual thread tasks to inherit the parent's pipeline context.
     */
    public static void restoreMdcFromScope() {
        if (PIPELINE_CONTEXT.isBound()) {
            PIPELINE_CONTEXT.get().forEach(MDC::put);
        }
    }

    /**
     * Get the current pipeline context (works from any virtual thread).
     */
    public static Optional<Map<String, String>> currentContext() {
        return PIPELINE_CONTEXT.isBound()
            ? Optional.of(PIPELINE_CONTEXT.get())
            : Optional.empty();
    }
}

