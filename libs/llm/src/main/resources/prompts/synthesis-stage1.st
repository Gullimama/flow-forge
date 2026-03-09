You are analyzing service interaction flow "{flowName}" in a Java 11 Micronaut microservice estate.

## Context
Flow Type: {flowType}
Services: {services}
Complexity: {complexity}

## Evidence
### Code Snippets
{codeEvidence}

### Runtime Log Patterns
{logPatterns}

### Knowledge Graph Paths
{graphContext}

---
Produce a complete flow analysis: describe the flow's purpose, list every inter-service interaction
(protocol, data exchanged, side-effects), describe the data flow (input → transformations → output),
and list external dependencies and assumptions.

Output JSON matching:
{format}
