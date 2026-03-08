package com.flowforge.topology.model;

public record IngressRule(String host, String path, String serviceName, int servicePort) {}
