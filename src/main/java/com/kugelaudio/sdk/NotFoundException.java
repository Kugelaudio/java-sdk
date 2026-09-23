package com.kugelaudio.sdk;

/**
 * Thrown when a referenced resource doesn't exist or isn't visible to the
 * caller (HTTP 404, error_code {@code NOT_FOUND}).
 *
 * <p>Surfaced e.g. when an unknown {@code voiceId} is passed, when a voice
 * belongs to another org, or when a resource has been deleted. Distinct
 * from {@link ValidationException} (malformed request) so callers can show
 * "not found" UX without matching on message text.
 */
public class NotFoundException extends KugelAudioException {

    public static final String DEFAULT_MESSAGE = "Not found.";

    public NotFoundException() {
        super(DEFAULT_MESSAGE, 404, "NOT_FOUND", null, null, null);
    }

    public NotFoundException(String message) {
        super(message, 404, "NOT_FOUND", null, null, null);
    }

    public NotFoundException(
            String message,
            int statusCode,
            String requestId,
            Throwable cause) {
        super(message, statusCode, "NOT_FOUND", requestId, null, cause);
    }

    public NotFoundException(
            String message,
            int statusCode,
            String requestId,
            Integer retryAfter,
            Throwable cause) {
        super(message, statusCode, "NOT_FOUND", requestId, retryAfter, cause);
    }
}
