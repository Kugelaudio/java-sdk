package com.kugelaudio.sdk;

/**
 * Thrown when the API rate limit is exceeded (HTTP 429).
 */
public class RateLimitException extends KugelAudioException {

    public RateLimitException(String message) {
        super(message, 429);
    }
}
