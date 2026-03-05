package com.kugelaudio.sdk;

/**
 * Thrown when the account has insufficient credits (HTTP 403 or WS close code 4003).
 */
public class InsufficientCreditsException extends KugelAudioException {

    public InsufficientCreditsException(String message) {
        super(message, 403);
    }
}
