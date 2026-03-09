package com.flowforge.observability.tracing;

import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SpanProcessor;
import org.springframework.stereotype.Component;

@Component
public class FlowForgeSpanProcessor implements SpanProcessor {

    @Override
    public void onStart(Context parentContext, ReadWriteSpan span) {
        String version = getClass().getPackage() != null
            ? getClass().getPackage().getImplementationVersion()
            : null;
        span.setAttribute("flowforge.version", version != null ? version : "dev");
    }

    @Override
    public boolean isStartRequired() {
        return true;
    }

    @Override
    public void onEnd(ReadableSpan span) {
        // no-op
    }

    @Override
    public boolean isEndRequired() {
        return false;
    }
}

