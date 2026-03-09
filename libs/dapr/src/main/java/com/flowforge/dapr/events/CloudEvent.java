package com.flowforge.dapr.events;

public record CloudEvent<T>(
    String id,
    String source,
    String type,
    String specversion,
    T data
) {}

