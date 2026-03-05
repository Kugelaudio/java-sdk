package com.kugelaudio.sdk;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ExceptionTest {

    @Test
    void authenticationException() {
        AuthenticationException ex = new AuthenticationException("bad key");
        assertEquals(401, ex.getStatusCode());
        assertEquals("bad key", ex.getMessage());
        assertInstanceOf(KugelAudioException.class, ex);
    }

    @Test
    void insufficientCreditsException() {
        InsufficientCreditsException ex = new InsufficientCreditsException("no credits");
        assertEquals(403, ex.getStatusCode());
        assertInstanceOf(KugelAudioException.class, ex);
    }

    @Test
    void rateLimitException() {
        RateLimitException ex = new RateLimitException("too fast");
        assertEquals(429, ex.getStatusCode());
        assertInstanceOf(KugelAudioException.class, ex);
    }

    @Test
    void validationException() {
        ValidationException ex = new ValidationException("bad request");
        assertEquals(400, ex.getStatusCode());
        assertInstanceOf(KugelAudioException.class, ex);
    }

    @Test
    void connectionException() {
        ConnectionException ex = new ConnectionException("disconnected");
        assertEquals(503, ex.getStatusCode());
        assertInstanceOf(KugelAudioException.class, ex);
    }

    @Test
    void baseExceptionWithCause() {
        RuntimeException cause = new RuntimeException("root cause");
        KugelAudioException ex = new KugelAudioException("wrapped", cause);
        assertEquals("wrapped", ex.getMessage());
        assertSame(cause, ex.getCause());
        assertEquals(0, ex.getStatusCode());
    }
}
