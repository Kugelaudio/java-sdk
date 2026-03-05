package com.kugelaudio.sdk;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GenerateRequestTest {

    @Test
    void builderWithDefaults() {
        GenerateRequest request = GenerateRequest.builder("Hello").build();

        assertEquals("Hello", request.getText());
        assertEquals("kugel-1-turbo", request.getModelId());
        assertEquals(2.0, request.getCfgScale());
        assertEquals(2048, request.getMaxNewTokens());
        assertEquals(24000, request.getSampleRate());
        assertTrue(request.getNormalize());
        assertNull(request.getLanguage());
        assertNull(request.getWordTimestamps());
    }

    @Test
    void builderWithCustomValues() {
        GenerateRequest request = GenerateRequest.builder("Test")
                .modelId("kugel-1")
                .voiceId(123)
                .cfgScale(3.0)
                .maxNewTokens(4096)
                .sampleRate(16000)
                .normalize(false)
                .language("en")
                .wordTimestamps(true)
                .build();

        assertEquals("Test", request.getText());
        assertEquals("kugel-1", request.getModelId());
        assertEquals(123, request.getVoiceId());
        assertEquals(3.0, request.getCfgScale());
        assertEquals(4096, request.getMaxNewTokens());
        assertEquals(16000, request.getSampleRate());
        assertFalse(request.getNormalize());
        assertEquals("en", request.getLanguage());
        assertTrue(request.getWordTimestamps());
    }

    @Test
    void blankTextThrows() {
        assertThrows(IllegalArgumentException.class, () -> GenerateRequest.builder("").build());
        assertThrows(IllegalArgumentException.class, () -> GenerateRequest.builder("   ").build());
    }

    @Test
    void nullTextThrows() {
        assertThrows(IllegalArgumentException.class, () -> GenerateRequest.builder(null).build());
    }
}
