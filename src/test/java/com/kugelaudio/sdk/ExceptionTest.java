package com.kugelaudio.sdk;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ExceptionTest {

    @Test
    void baseExceptionWithCause() {
        RuntimeException cause = new RuntimeException("root cause");
        KugelAudioException ex = new KugelAudioException("wrapped", cause);
        assertEquals("wrapped", ex.getMessage());
        assertSame(cause, ex.getCause());
        assertEquals(0, ex.getStatusCode());
    }

}
