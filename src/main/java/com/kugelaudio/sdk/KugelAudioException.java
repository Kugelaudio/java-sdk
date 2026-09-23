package com.kugelaudio.sdk;

/**
 * Base exception for all KugelAudio SDK errors.
 *
 * <p>Carries optional context about the failure:
 * <ul>
 *   <li>{@link #getStatusCode() statusCode} — HTTP status when known.</li>
 *   <li>{@link #getErrorCode() errorCode} — machine-readable code mirrored
 *       from the server enum (see server-side {@code ErrorCode}).</li>
 *   <li>{@link #getRequestId() requestId} — correlation ID echoed by the
 *       server via {@code x-request-id} when present.</li>
 *   <li>{@link #getRetryAfter() retryAfter} — seconds hint for 429/503
 *       responses when provided.</li>
 * </ul>
 */
public class KugelAudioException extends RuntimeException {

    private final int statusCode;
    private final String errorCode;
    private final String requestId;
    private final Integer retryAfter;

    public KugelAudioException(String message) {
        this(message, 0, null, null, null, null);
    }

    public KugelAudioException(String message, int statusCode) {
        this(message, statusCode, null, null, null, null);
    }

    public KugelAudioException(String message, Throwable cause) {
        this(message, 0, null, null, null, cause);
    }

    public KugelAudioException(String message, int statusCode, Throwable cause) {
        this(message, statusCode, null, null, null, cause);
    }

    public KugelAudioException(
            String message,
            int statusCode,
            String errorCode,
            String requestId,
            Integer retryAfter,
            Throwable cause) {
        super(formatMessage(message, requestId), cause);
        this.statusCode = statusCode;
        this.errorCode = errorCode;
        this.requestId = requestId;
        this.retryAfter = retryAfter;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public String getRequestId() {
        return requestId;
    }

    public Integer getRetryAfter() {
        return retryAfter;
    }

    private static String formatMessage(String message, String requestId) {
        if (requestId != null && !requestId.isBlank()) {
            return message + " (request_id: " + requestId + ")";
        }
        return message;
    }
}
