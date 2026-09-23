package com.kugelaudio.sdk;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class KugelAudioOptionsTest {

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
    void nullApiKeyThrows() {
        assertThrows(NullPointerException.class, () ->
                KugelAudioOptions.builder(null).build());
    }

    // -----------------------------------------------------------------------
    // Multi-region tests
    // -----------------------------------------------------------------------

    @Test
    void explicitRegionEu() {
        KugelAudioOptions options = KugelAudioOptions.builder("ka_test123")
                .region(Region.EU)
                .build();
        assertEquals("https://api.eu.kugelaudio.com", options.getApiUrl());
    }

    @Test
    void keyPrefixEuDetectedAndStripped() {
        KugelAudioOptions options = KugelAudioOptions.builder("eu-ka_test123").build();
        assertEquals("https://api.eu.kugelaudio.com", options.getApiUrl());
        assertEquals("ka_test123", options.getApiKey());
    }

    @Test
    void explicitRegionOverridesKeyPrefix() {
        KugelAudioOptions options = KugelAudioOptions.builder("us-ka_test123")
                .region(Region.GLOBAL)
                .build();
        assertEquals("https://api.kugelaudio.com", options.getApiUrl());
        assertEquals("ka_test123", options.getApiKey());
    }

    @Test
    void apiUrlOverridesRegion() {
        KugelAudioOptions options = KugelAudioOptions.builder("us-ka_test123")
                .region(Region.GLOBAL)
                .apiUrl("https://custom.example.com")
                .build();
        assertEquals("https://custom.example.com", options.getApiUrl());
        assertEquals("ka_test123", options.getApiKey());
    }

    @Test
    void effectiveTtsUrlFollowsRegion() {
        KugelAudioOptions options = KugelAudioOptions.builder("us-ka_test123").build();
        assertEquals("https://api.kugelaudio.com", options.getEffectiveTtsUrl());
    }

    // -----------------------------------------------------------------------
    // Keepalive ping tests
    // -----------------------------------------------------------------------

}
