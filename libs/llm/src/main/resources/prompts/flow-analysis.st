You are an expert Java architect analyzing a legacy Java 11 Micronaut reactive microservice system.

## Flow Under Analysis: {flowName}
Flow Type: {flowType}
Services Involved: {services}

## Source Code Evidence
{codeEvidence}

## Runtime Log Patterns
{logPatterns}

## Knowledge Graph Context
{graphContext}

---
Task: Analyze this system flow end-to-end. For each inter-service interaction, identify the protocol
(HTTP, Kafka, gRPC), the data exchanged, and any side-effects. Describe the overall data flow
(input → transformations → output) and list external dependencies.

Produce your analysis as JSON matching this schema:
{format}
