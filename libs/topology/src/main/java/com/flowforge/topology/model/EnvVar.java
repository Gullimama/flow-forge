package com.flowforge.topology.model;

import java.util.Optional;

public record EnvVar(String name, String value, Optional<String> secretRef) {}
