package com.kugelaudio.sdk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Tests for per-session usage parsing from session_closed (KUG-1192). */
class SessionUsageTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SessionUsage parse(String json) throws Exception {
        JsonNode node = MAPPER.readTree(json);
        return SessionUsage.fromSessionClosed(node);
    }

    @Test
    void parsesChargedUsage() throws Exception {
        SessionUsage usage = parse("""
                {
                  "session_closed": true,
                  "total_audio_seconds": 5.4,
                  "usage": {
                    "audio_seconds": 5.4,
                    "characters": 142,
                    "cost_cents": 0.49,
                    "currency": "eur",
                    "model_id": "kugel-3"
                  }
                }
                """);
        assertNotNull(usage);
        assertEquals(5.4, usage.getAudioSeconds(), 1e-9);
        assertEquals(142, usage.getCharacters());
        assertEquals(0.49, usage.getCostCents(), 1e-9);
        assertEquals("eur", usage.getCurrency());
        assertEquals("kugel-3", usage.getModelId());
        assertTrue(usage.isCostAvailable());
    }

    @Test
    void unavailableCostIsNullNotZero() throws Exception {
        SessionUsage usage = parse("""
                {
                  "session_closed": true,
                  "total_audio_seconds": 2.0,
                  "usage": {
                    "audio_seconds": 2.0,
                    "cost_cents": null,
                    "cost_unavailable": true,
                    "model_id": "kugel-3"
                  }
                }
                """);
        assertNotNull(usage);
        assertNull(usage.getCostCents());
        assertFalse(usage.isCostAvailable());
        assertEquals(2.0, usage.getAudioSeconds(), 1e-9);
    }

    @Test
    void legacyServerWithoutUsageBlock() throws Exception {
        SessionUsage usage = parse("""
                { "session_closed": true, "total_audio_seconds": 3.0 }
                """);
        assertNotNull(usage);
        assertEquals(3.0, usage.getAudioSeconds(), 1e-9);
        assertNull(usage.getCostCents());
        assertFalse(usage.isCostAvailable());
    }

}
