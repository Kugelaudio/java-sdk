package com.kugelaudio.sdk;

/**
 * Thrown when the API key was missing, malformed, or rejected by the server
 * (HTTP 401, error_code {@code UNAUTHORIZED}, or WebSocket close code 4001).
 */
public class AuthenticationException extends KugelAudioException {

    public static final String DEFAULT_MESSAGE =
            "KugelAudio rejected the API key. "
                    + "Check it is current at https://app.kugelaudio.com/settings/api-keys.";

    public AuthenticationException() {
        super(DEFAULT_MESSAGE, 401, "UNAUTHORIZED", null, null, null);
    }

    public AuthenticationException(String message) {
        super(message, 401, "UNAUTHORIZED", null, null, null);
    }

    public AuthenticationException(String message, Throwable cause) {
        super(message, 401, "UNAUTHORIZED", null, null, cause);
    }

    public AuthenticationException(
            String message,
            int statusCode,
            String requestId,
            Throwable cause) {
        super(message, statusCode, "UNAUTHORIZED", requestId, null, cause);
    }

    public AuthenticationException(
            String message,
            int statusCode,
            String requestId,
            Integer retryAfter,
            Throwable cause) {
        super(message, statusCode, "UNAUTHORIZED", requestId, retryAfter, cause);
    }
}
