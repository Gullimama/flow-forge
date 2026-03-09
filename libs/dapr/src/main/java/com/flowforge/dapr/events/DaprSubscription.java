package com.flowforge.dapr.events;

public record DaprSubscription(
    String pubsubname,
    String topic,
    String route
) {}

