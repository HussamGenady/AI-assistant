package com.enterprise.ai.advisor;

public class GuardrailViolationException extends RuntimeException {

    public GuardrailViolationException(String message) {
        super(message);
    }
}
