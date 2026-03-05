package com.kugelaudio.sdk;

import com.kugelaudio.sdk.internal.WsUrlBuilder;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WsUrlBuilderTest {

    @Test
    void buildWithApiKey() {
        KugelAudioOptions options = KugelAudioOptions.builder("test-key").build();
        String url = WsUrlBuilder.build(options, "/ws/tts");

        assertTrue(url.startsWith("wss://"));
        assertTrue(url.contains("/ws/tts"));
        assertTrue(url.contains("api_key=test-key"));
    }

    @Test
    void buildWithMasterKey() {
        KugelAudioOptions options = KugelAudioOptions.builder("master-secret")
                .masterKey()
                .build();
        String url = WsUrlBuilder.build(options, "/ws/tts");

        assertTrue(url.contains("master_key=master-secret"));
    }

    @Test
    void buildWithToken() {
        KugelAudioOptions options = KugelAudioOptions.builder("jwt-tok")
                .token()
                .orgId(42)
                .build();
        String url = WsUrlBuilder.build(options, "/ws/tts");

        assertTrue(url.contains("token=jwt-tok"));
        assertTrue(url.contains("org_id=42"));
    }

    @Test
    void buildWithCustomTtsUrl() {
        KugelAudioOptions options = KugelAudioOptions.builder("key")
                .ttsUrl("https://tts.custom.com")
                .build();
        String url = WsUrlBuilder.build(options, "/ws/tts/stream");

        assertTrue(url.startsWith("wss://tts.custom.com/ws/tts/stream"));
    }

    @Test
    void buildConvertsHttpToWs() {
        KugelAudioOptions options = KugelAudioOptions.builder("key")
                .ttsUrl("http://localhost:8080")
                .build();
        String url = WsUrlBuilder.build(options, "/ws/tts");

        assertTrue(url.startsWith("ws://localhost:8080/ws/tts"));
    }

    @Test
    void buildWithMultiContextPath() {
        KugelAudioOptions options = KugelAudioOptions.builder("key").build();
        String url = WsUrlBuilder.build(options, "/ws/tts/multi");

        assertTrue(url.contains("/ws/tts/multi"));
    }

    @Test
    void buildWithInitialMessage() {
        KugelAudioOptions options = KugelAudioOptions.builder("key").build();
        String msg = "{\"text\":\"Hello\",\"voice_id\":123}";
        String url = WsUrlBuilder.build(options, "/ws/tts", msg);

        assertTrue(url.contains("initial_message="));
        assertTrue(url.contains("api_key=key"));
    }

    @Test
    void buildWithoutInitialMessage() {
        KugelAudioOptions options = KugelAudioOptions.builder("key").build();
        String url = WsUrlBuilder.build(options, "/ws/tts", null);

        assertFalse(url.contains("initial_message"));
    }
}
