package com.kugelaudio.sdk;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.kugelaudio.sdk.internal.Diagnostics;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.http.HttpClient;
import java.net.http.WebSocket;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the mid-connection {@code updateSettings()} support (KUG-1166)
 * that don't require a live server: body construction, the empty-update guard,
 * the not-connected guard, and the effective-settings parse. The full
 * send → {@code settings_updated} ack round-trip is exercised live.
 */
class UpdateSettingsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void buildSettingsBodyEmitsSnakeCaseAndOnlyProvidedFields() {
        ObjectNode body = StreamingSession.buildSettingsBody(
                SettingsUpdate.builder().cfgScale(1.5).speed(1.1).build());

        assertEquals(1.5, body.get("cfg_scale").asDouble());
        assertEquals(1.1, body.get("speed").asDouble());
        assertFalse(body.has("temperature"), "unset fields must be omitted");
        assertEquals(2, body.size());
    }

    @Test
    void buildSettingsBodyMapsEveryField() {
        ObjectNode body = StreamingSession.buildSettingsBody(SettingsUpdate.builder()
                .cfgScale(1.0).temperature(0.2).speed(1.2)
                .maxNewTokens(2048).language("de").normalize(false).build());

        assertEquals(1.0, body.get("cfg_scale").asDouble());
        assertEquals(0.2, body.get("temperature").asDouble());
        assertEquals(1.2, body.get("speed").asDouble());
        assertEquals(2048, body.get("max_new_tokens").asInt());
        assertEquals("de", body.get("language").asText());
        assertFalse(body.get("normalize").asBoolean());
    }

    @Test
    void emptyUpdateThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> StreamingSession.buildSettingsBody(SettingsUpdate.builder().build()));
    }

    @Test
    void updateSettingsBeforeConnectThrows() {
        KugelAudioOptions options = KugelAudioOptions.builder("test-key").build();
        StreamConfig config = StreamConfig.builder().voiceId(1).build();
        StreamingSession session = new StreamingSession(
                options, HttpClient.newHttpClient(), config, new StreamCallbacks() {
            @Override public void onChunk(AudioChunk chunk) {}
        }, Diagnostics.disabled());

        assertThrows(KugelAudioException.class,
                () -> session.updateSettings(SettingsUpdate.builder().cfgScale(1.5).build()));
    }

    @Test
    void streamingRejectedUpdateDoesNotPoisonNextConfigSend() throws Exception {
        KugelAudioOptions options = KugelAudioOptions.builder("test-key").build();
        StreamConfig config = StreamConfig.builder().voiceId(1).cfgScale(2.0).build();
        StreamingSession session = new StreamingSession(
                options, HttpClient.newHttpClient(), config, new StreamCallbacks() {
            @Override public void onChunk(AudioChunk chunk) {}
        }, Diagnostics.disabled());
        FakeWebSocket ws = new FakeWebSocket();
        setWebSocket(session, ws);
        ws.onSend = payload -> {
            if (payload.contains("\"update_settings\"")) {
                sendSettingsFrame(session, validationErrorFrame());
            }
        };

        assertThrows(KugelAudioException.class,
                () -> session.updateSettings(SettingsUpdate.builder().cfgScale(99.0).build()));

        session.send("Hello.");
        ObjectNode next = (ObjectNode) readLastSent(ws);
        assertEquals(2.0, next.get("cfg_scale").asDouble());
    }

    @Test
    void multiRejectedUpdateDoesNotPoisonNextContextCreate() throws Exception {
        KugelAudioOptions options = KugelAudioOptions.builder("test-key").build();
        MultiContextSession session = new MultiContextSession(
                options, HttpClient.newHttpClient(), MultiContextConfig.builder().build(),
                Diagnostics.disabled());
        FakeWebSocket ws = new FakeWebSocket();
        setWebSocket(session, ws);
        ws.onSend = payload -> {
            if (payload.contains("\"update_settings\"")) {
                sendSettingsFrame(session, validationErrorFrame());
            }
        };

        assertThrows(KugelAudioException.class,
                () -> session.updateSettings(SettingsUpdate.builder().cfgScale(99.0).build()));

        session.createContext("narrator");
        ObjectNode next = (ObjectNode) readLastSent(ws);
        assertFalse(next.has("cfg_scale"));
    }

    @Test
    void effectiveSettingsParsesServerEcho() {
        ObjectNode echo = MAPPER.createObjectNode();
        echo.put("cfg_scale", 1.0);
        echo.putNull("temperature");
        echo.put("speed", 1.2);
        echo.put("max_new_tokens", 2048);
        echo.putNull("language");
        echo.put("normalize", true);

        EffectiveSettings eff = EffectiveSettings.fromJson(echo);

        assertEquals(1.0, eff.getCfgScale());
        assertNull(eff.getTemperature(), "null echo field stays null");
        assertEquals(1.2, eff.getSpeed());
        assertEquals(2048, eff.getMaxNewTokens());
        assertNull(eff.getLanguage());
        assertTrue(eff.getNormalize());
    }

    private static ObjectNode validationErrorFrame() {
        ObjectNode frame = MAPPER.createObjectNode();
        frame.put("error", "Invalid settings update: cfg_scale: out of range");
        frame.put("error_code", "VALIDATION_ERROR");
        frame.put("code", 400);
        return frame;
    }

    private static void setWebSocket(Object session, WebSocket ws) throws Exception {
        Field field = session.getClass().getDeclaredField("ws");
        field.setAccessible(true);
        field.set(session, ws);
    }

    private static void sendSettingsFrame(Object session, ObjectNode frame) {
        try {
            Method method = session.getClass().getDeclaredMethod(
                    "onSettingsFrame", com.fasterxml.jackson.databind.JsonNode.class);
            method.setAccessible(true);
            method.invoke(session, frame);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static ObjectNode readLastSent(FakeWebSocket ws) throws Exception {
        return (ObjectNode) MAPPER.readTree(ws.sent.get(ws.sent.size() - 1));
    }
}
