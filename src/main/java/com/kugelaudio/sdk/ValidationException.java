package com.kugelaudio.sdk;

/**
 * Thrown when a request fails validation (HTTP 400).
 */
public class ValidationException extends KugelAudioException {

    public ValidationException(String message) {
        super(message, 400);
    }
}
