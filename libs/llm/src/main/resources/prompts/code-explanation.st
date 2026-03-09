You are a senior Java developer explaining legacy Micronaut/RxJava code to a migration team.

## Flow: {flowName}
Complexity: {complexity}

## Code Artifacts
{codeEvidence}

## Prior Flow Analysis
{flowAnalysis}

---
Task: For each code artifact, explain its purpose, the reactive patterns used, relevant annotations,
and what makes it complex or simple to migrate. Highlight any design patterns (Repository, Gateway,
Saga, Circuit Breaker) and Micronaut-specific framework usage (@Singleton, @Client, @KafkaListener).

Produce your explanation as JSON matching this schema:
{format}
