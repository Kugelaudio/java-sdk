package com.kugelaudio.sdk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kugelaudio.sdk.internal.Diagnostics;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ENG-560: {@code project_id} reaches the wire on generate, streaming and
 * multi-context requests so pronunciation dictionaries apply at synthesis,
 * and is absent when the caller did not set it.
 */
class ProjectIdTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final KugelAudioOptions OPTIONS = KugelAudioOptions.builder("test-key").build();

    @Test
    void generatePayloadCarriesProjectIdWithDictionaryIds() throws Exception {
        // Round-trip through JSON: dictionary_ids is a POJO node until serialized.
        JsonNode payload = MAPPER.readTree(MAPPER.writeValueAsString(
                TTSResource.buildPayload(GenerateRequest.builder("Hi")
                        .language("en")
                        .projectId(42)
                        .dictionaryIds(List.of(7))
                        .build())));
        assertEquals(42L, payload.get("project_id").asLong());
        assertEquals(7, payload.get("dictionary_ids").get(0).asInt());
    }

    @Test
    void generatePayloadOmitsProjectIdWhenUnset() {
        JsonNode payload = TTSResource.buildPayload(
                GenerateRequest.builder("Hi").language("en").build());
        assertFalse(payload.has("project_id"));
    }

    @Test
    void streamingSessionSendsProjectIdInConfig() throws Exception {
        assertEquals(42L, firstStreamingMessage(
                StreamConfig.builder().language("en").projectId(42).build())
                .get("project_id").asLong());
        assertFalse(firstStreamingMessage(StreamConfig.builder().language("en").build())
                .has("project_id"));
    }

    @Test
    void multiContextSessionSendsProjectIdOnContextCreate() throws Exception {
        assertEquals(42L, contextCreateMessage(
                MultiContextConfig.builder().language("en").projectId(42).build())
                .get("project_id").asLong());
        assertFalse(contextCreateMessage(MultiContextConfig.builder().language("en").build())
                .has("project_id"));
    }

    private static JsonNode firstStreamingMessage(StreamConfig config) throws Exception {
        StreamingSession session = new StreamingSession(
                OPTIONS, HttpClient.newHttpClient(), config, new StreamCallbacks() {
            @Override public void onChunk(AudioChunk chunk) {}
        }, Diagnostics.disabled());
        FakeWebSocket ws = new FakeWebSocket();
        setWebSocket(session, ws);
        session.send("Hello.");
        return MAPPER.readTree(ws.sent.get(0));
    }

    private static JsonNode contextCreateMessage(MultiContextConfig config) throws Exception {
        MultiContextSession session = new MultiContextSession(
                OPTIONS, HttpClient.newHttpClient(), config, Diagnostics.disabled());
        FakeWebSocket ws = new FakeWebSocket();
        setWebSocket(session, ws);
        session.createContext("narrator");
        return MAPPER.readTree(ws.sent.get(0));
    }

    private static void setWebSocket(Object session, WebSocket ws) throws Exception {
        Field field = session.getClass().getDeclaredField("ws");
        field.setAccessible(true);
        field.set(session, ws);
    }
}
