package com.kugelaudio.sdk;

/**
 * Thrown when the SDK cannot reach the KugelAudio API (network error,
 * timeout, server down, or model deployment temporarily unavailable).
 * Covers HTTP 503 ({@code MODEL_UNAVAILABLE}) and generic transport failures.
 */
public class ConnectionException extends KugelAudioException {

    public ConnectionException(String message) {
        super(message, 503, null, null, null, null);
    }

    public ConnectionException(String message, Throwable cause) {
        super(message, 503, null, null, null, cause);
    }

    public ConnectionException(
            String message,
            int statusCode,
            String errorCode,
            String requestId,
            Integer retryAfter,
            Throwable cause) {
        super(message, statusCode, errorCode, requestId, retryAfter, cause);
    }
}
