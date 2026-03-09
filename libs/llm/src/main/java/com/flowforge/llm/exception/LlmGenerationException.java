package com.flowforge.llm.exception;

/**
 * Thrown when LLM generation fails and no fallback is possible.
 * Callers should catch this to implement stage-specific degradation.
 */
public class LlmGenerationException extends RuntimeException {

    public LlmGenerationException(String message, Throwable cause) {
        super(message, cause);
    }
}
