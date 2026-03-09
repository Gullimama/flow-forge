package com.flowforge.synthesis.model;

/**
 * Coupling between two services and decoupling strategy.
 */
public record CouplingPoint(
    String service1,
    String service2,
    String couplingType,
    String description,
    String decouplingStrategy
) {
}
