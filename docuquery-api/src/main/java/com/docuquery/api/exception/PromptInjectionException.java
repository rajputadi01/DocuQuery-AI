package com.docuquery.api.exception;

public class PromptInjectionException extends RuntimeException {
    public PromptInjectionException(String message) {
        super(message);
    }
}