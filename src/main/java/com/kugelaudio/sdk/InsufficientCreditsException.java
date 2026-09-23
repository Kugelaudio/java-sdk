package com.kugelaudio.sdk;

/**
 * Thrown when the account has no remaining TTS credits (HTTP 402,
 * error_code {@code INSUFFICIENT_CREDITS}, or WebSocket close code 4003).
 */
public class InsufficientCreditsException extends KugelAudioException {

    public static final String DEFAULT_MESSAGE =
            "Your KugelAudio account is out of credits. Top up at "
                    + "https://app.kugelaudio.com/billing.";

    public InsufficientCreditsException() {
        super(DEFAULT_MESSAGE, 402, "INSUFFICIENT_CREDITS", null, null, null);
    }

    public InsufficientCreditsException(String message) {
        super(message, 402, "INSUFFICIENT_CREDITS", null, null, null);
    }

    public InsufficientCreditsException(
            String message,
            int statusCode,
            String requestId,
            Throwable cause) {
        super(message, statusCode, "INSUFFICIENT_CREDITS", requestId, null, cause);
    }

    public InsufficientCreditsException(
            String message,
            int statusCode,
            String requestId,
            Integer retryAfter,
            Throwable cause) {
        super(message, statusCode, "INSUFFICIENT_CREDITS", requestId, retryAfter, cause);
    }
}
