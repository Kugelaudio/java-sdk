package com.kugelaudio.sdk;

/**
 * Thrown when a connection to the KugelAudio API cannot be established.
 */
public class ConnectionException extends KugelAudioException {

    public ConnectionException(String message) {
        super(message, 503);
    }

    public ConnectionException(String message, Throwable cause) {
        super(message, 503, cause);
    }
}
