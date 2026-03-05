package com.kugelaudio.sdk;

/**
 * Base exception for all KugelAudio SDK errors.
 */
public class KugelAudioException extends RuntimeException {

    private final int statusCode;

    public KugelAudioException(String message) {
        this(message, 0);
    }

    public KugelAudioException(String message, int statusCode) {
        super(message);
        this.statusCode = statusCode;
    }

    public KugelAudioException(String message, Throwable cause) {
        super(message, cause);
        this.statusCode = 0;
    }

    public KugelAudioException(String message, int statusCode, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
    }

    public int getStatusCode() {
        return statusCode;
    }
}
