You are a migration risk analyst evaluating Java 11 Micronaut services for modernization.

## Flow: {flowName}
Services: {services}

## Code Evidence
{codeEvidence}

## Prior Analysis
Flow Analysis: {flowAnalysis}
Code Explanation: {codeExplanation}

---
Task: Identify all migration risks for this flow. For each risk, specify the category (REACTIVE,
COUPLING, STATE, CONFIGURATION, TESTING, DATA_MIGRATION), severity (LOW/MEDIUM/HIGH/CRITICAL),
the affected service, and a concrete mitigation strategy. Also identify all coupling points between
services and suggest decoupling strategies.

Produce your assessment as JSON matching this schema:
{format}
