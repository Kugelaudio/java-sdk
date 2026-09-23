package com.kugelaudio.sdk;

/**
 * Thrown when the API rate limit is exceeded (HTTP 429,
 * error_code {@code RATE_LIMITED}, or WebSocket close code 4029).
 */
public class RateLimitException extends KugelAudioException {

    public RateLimitException() {
        this(null, null);
    }

    public RateLimitException(String message) {
        this(message, null);
    }

    public RateLimitException(String message, Integer retryAfter) {
        super(
                message != null
                        ? message
                        : (retryAfter != null
                                ? "KugelAudio rate limit hit; retry after " + retryAfter + "s."
                                : "KugelAudio rate limit hit; retry shortly."),
                429,
                "RATE_LIMITED",
                null,
                retryAfter,
                null);
    }

    public RateLimitException(
            String message,
            int statusCode,
            String requestId,
            Integer retryAfter,
            Throwable cause) {
        super(message, statusCode, "RATE_LIMITED", requestId, retryAfter, cause);
    }

    public RateLimitException(
            String message,
            int statusCode,
            String errorCode,
            String requestId,
            Integer retryAfter,
            Throwable cause) {
        super(message, statusCode, errorCode, requestId, retryAfter, cause);
    }
}
