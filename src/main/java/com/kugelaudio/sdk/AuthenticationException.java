package com.kugelaudio.sdk;

/**
 * Thrown when API key authentication fails (HTTP 401 or WS close code 4001).
 */
public class AuthenticationException extends KugelAudioException {

    public AuthenticationException(String message) {
        super(message, 401);
    }

    public AuthenticationException(String message, Throwable cause) {
        super(message, 401, cause);
    }
}
