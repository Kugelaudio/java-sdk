package com.kugelaudio.sdk;

/**
 * Thrown when a request fails validation (HTTP 400,
 * error_code {@code VALIDATION_ERROR}).
 */
public class ValidationException extends KugelAudioException {

    public ValidationException(String message) {
        super(message, 400, "VALIDATION_ERROR", null, null, null);
    }

    public ValidationException(
            String message,
            int statusCode,
            String requestId,
            Throwable cause) {
        super(message, statusCode, "VALIDATION_ERROR", requestId, null, cause);
    }

    public ValidationException(
            String message,
            int statusCode,
            String requestId,
            Integer retryAfter,
            Throwable cause) {
        super(message, statusCode, "VALIDATION_ERROR", requestId, retryAfter, cause);
    }

    public ValidationException(
            String message,
            int statusCode,
            String errorCode,
            String requestId,
            Integer retryAfter,
            Throwable cause) {
        super(message, statusCode, errorCode, requestId, retryAfter, cause);
    }
}
