package com.kugelaudio.sdk;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class KugelAudioOptionsTest {

    @Test
    void builderWithDefaults() {
        KugelAudioOptions options = KugelAudioOptions.builder("test-key").build();

        assertEquals("test-key", options.getApiKey());
        assertEquals(KugelAudioOptions.AuthMode.API_KEY, options.getAuthMode());
        assertNull(options.getOrgId());
        assertEquals("https://api.kugelaudio.com", options.getApiUrl());
        assertNull(options.getTtsUrl());
        assertEquals(Duration.ofSeconds(60), options.getTimeout());
    }

    @Test
    void builderWithCustomValues() {
        KugelAudioOptions options = KugelAudioOptions.builder("my-key")
                .apiUrl("https://custom.api.com")
                .ttsUrl("https://custom.tts.com")
                .timeout(Duration.ofSeconds(30))
                .build();

        assertEquals("my-key", options.getApiKey());
        assertEquals("https://custom.api.com", options.getApiUrl());
        assertEquals("https://custom.tts.com", options.getTtsUrl());
        assertEquals(Duration.ofSeconds(30), options.getTimeout());
    }

    @Test
    void masterKeyAuthMode() {
        KugelAudioOptions options = KugelAudioOptions.builder("master-key")
                .masterKey()
                .build();

        assertEquals(KugelAudioOptions.AuthMode.MASTER_KEY, options.getAuthMode());
    }

    @Test
    void tokenAuthRequiresOrgId() {
        assertThrows(IllegalArgumentException.class, () ->
                KugelAudioOptions.builder("token").token().build());
    }

    @Test
    void tokenAuthWithOrgId() {
        KugelAudioOptions options = KugelAudioOptions.builder("jwt-token")
                .token()
                .orgId(42)
                .build();

        assertEquals(KugelAudioOptions.AuthMode.TOKEN, options.getAuthMode());
        assertEquals(42, options.getOrgId());
    }

    @Test
    void effectiveTtsUrlFallsBackToApiUrl() {
        KugelAudioOptions options = KugelAudioOptions.builder("key").build();
        assertEquals("https://api.kugelaudio.com", options.getEffectiveTtsUrl());
    }

    @Test
    void effectiveTtsUrlUsesTtsUrlWhenSet() {
        KugelAudioOptions options = KugelAudioOptions.builder("key")
                .ttsUrl("https://tts.example.com")
                .build();
        assertEquals("https://tts.example.com", options.getEffectiveTtsUrl());
    }

    @Test
    void nullApiKeyThrows() {
        assertThrows(NullPointerException.class, () ->
                KugelAudioOptions.builder(null).build());
    }

    @Test
    void defaultKeepalivePingIntervalIs20Seconds() {
        KugelAudioOptions options = KugelAudioOptions.builder("test-key").build();
        assertEquals(Duration.ofSeconds(20), options.getKeepalivePingInterval());
    }

    @Test
    void customKeepalivePingInterval() {
        KugelAudioOptions options = KugelAudioOptions.builder("test-key")
                .keepalivePingInterval(Duration.ofSeconds(10))
                .build();
        assertEquals(Duration.ofSeconds(10), options.getKeepalivePingInterval());
    }

    @Test
    void keepalivePingIntervalCanBeDisabled() {
        KugelAudioOptions options = KugelAudioOptions.builder("test-key")
                .keepalivePingInterval(null)
                .build();
        assertNull(options.getKeepalivePingInterval());
    }
}
