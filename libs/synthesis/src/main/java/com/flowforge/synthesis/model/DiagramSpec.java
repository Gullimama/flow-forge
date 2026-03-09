package com.flowforge.synthesis.model;

/**
 * Mermaid or other diagram specification for the narrative.
 */
public record DiagramSpec(
    String title,
    DiagramSpec.DiagramType type,
    String mermaidCode
) {
    public enum DiagramType {
        SEQUENCE, FLOWCHART, C4_CONTAINER, STATE_MACHINE
    }
}
