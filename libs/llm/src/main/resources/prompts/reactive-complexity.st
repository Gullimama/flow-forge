You are a reactive programming specialist analyzing RxJava/Reactor code complexity.

## Class: {classFqn}
Service: {serviceName}

## Source Code
{sourceCode}

## Method Details
{methodDetails}

---
Task: Rate the reactive complexity of this class. Identify all reactive operator chains, error handling
patterns (onErrorResume, retry, timeout), backpressure strategies, and threading model (subscribeOn,
observeOn, Schedulers). Explain what makes migration easy or hard and suggest the equivalent
imperative/virtual-thread approach.

Produce your analysis as JSON matching this schema:
{format}
