package com.kugelaudio.sdk;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GenerateRequestTest {

    @Test
    void outputFormatOptIn() {
        GenerateRequest request = GenerateRequest.builder("Hi")
                .outputFormat("ulaw_8000")
                .build();
        assertEquals("ulaw_8000", request.getOutputFormat());
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

    @Test
    void cfgScaleOutOfBandClamped() {
        assertEquals(1.2, GenerateRequest.builder("Hi").cfgScale(1.0).build().getCfgScale());
        assertEquals(1.2, GenerateRequest.builder("Hi").cfgScale(-5.0).build().getCfgScale());
        assertEquals(2.5, GenerateRequest.builder("Hi").cfgScale(3.5).build().getCfgScale());
        assertEquals(2.5, GenerateRequest.builder("Hi").cfgScale(99.0).build().getCfgScale());
    }
}
