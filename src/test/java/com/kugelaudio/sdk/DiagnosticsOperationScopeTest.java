package com.kugelaudio.sdk;

import com.fasterxml.jackson.databind.JsonNode;
import com.kugelaudio.sdk.internal.Diagnostics;
import com.kugelaudio.sdk.internal.DiagnosticsConfig;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.net.http.HttpClient;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * "Operation scope" from {@code services/ingress/docs/sdk-diagnostics-contract.md}:
 * a streaming session reports one operation per turn, a multi-context session
 * one per context turn, and a cancellation never silences a later failure.
 * Frames are fed to the session's real listener over a {@link FakeWebSocket}.
 */
class DiagnosticsOperationScopeTest {

    private static final String AUDIO_FRAME =
            "{\"audio\":\"AAAAAA==\",\"enc\":\"pcm_s16le\",\"idx\":0,\"sr\":24000,\"samples\":2}";

    private static Diagnostics reporter(RecordingSender sender) {
        return Diagnostics.withSender(
                DiagnosticsConfig.of(
                        true, "https://api.kugelaudio.com/v1/sdk-diagnostics", DiagnosticsConfig.ENDPOINT_HOSTED),
                "2.4.0",
                sender,
                false);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static String errorFrame(String contextId, String requestId) {
        String context = contextId == null ? "" : "\"context_id\":\"" + contextId + "\",";
        return "{" + context + "\"error\":\"boom\",\"error_code\":\"INTERNAL_ERROR\",\"request_id\":\""
                + requestId + "\"}";
    }

    @Test
    void aBargeInEndsOnlyItsTurnAndLaterFailuresAreStillReported() throws Exception {
        RecordingSender sender = new RecordingSender();
        Diagnostics diagnostics = reporter(sender);
        StreamingSession session = new StreamingSession(
                KugelAudioOptions.builder("test-key").autoConnect(false).build(),
                HttpClient.newHttpClient(),
                StreamConfig.builder().voiceId(1).language("en").build(),
                new StreamCallbacks() { @Override public void onChunk(AudioChunk chunk) {} },
                diagnostics);
        FakeWebSocket ws = new FakeWebSocket();
        setField(session, "ws", ws);
        StreamingSession.SessionListener listener = session.new SessionListener();
        // The server acks a barge-in with an interrupted frame.
        ws.onSend = payload -> {
            if (payload.contains("\"cancel\"")) listener.onText(ws, "{\"interrupted\":true}", true);
        };

        // Turn 1: audio flows, then the user barges in.
        session.send("First turn.", true);
        listener.onText(ws, AUDIO_FRAME, true);
        session.cancelCurrent();

        // Turn 2: succeeds at its final frame.
        session.send("Second turn.", true);
        listener.onText(ws, AUDIO_FRAME, true);
        listener.onText(ws, "{\"final\":true}", true);

        // Turn 3: fails before any audio. Must be reported, scoped to turn 3.
        session.send("Third turn.", true);
        listener.onText(ws, errorFrame(null, "req_turn3"), true);

        assertEquals(1, diagnostics.cancelledCount());
        assertEquals(1, diagnostics.successCount());
        assertEquals(1, diagnostics.failureCount());

        diagnostics.flushNow();
        List<JsonNode> records = sender.records();
        assertEquals(1, records.size());
        Map<String, String> attrs = RecordingSender.attributesOf(records.get(0));
        assertEquals("request_failed", attrs.get("kugel.event"));
        assertEquals("stream_session", attrs.get("kugel.operation"));
        assertEquals("awaiting_first_audio", attrs.get("kugel.failure_stage"));
        assertEquals("0", attrs.get("kugel.audio_chunks"), "chunks of earlier turns do not leak in");
        assertEquals("0", attrs.get("kugel.retry_count"));
        assertEquals("req_turn3", attrs.get("kugel.server_request_id"));
    }

    @Test
    void anAbnormalCloseMidTurnIsAStreamInterruption() throws Exception {
        RecordingSender sender = new RecordingSender();
        Diagnostics diagnostics = reporter(sender);
        StreamingSession session = new StreamingSession(
                KugelAudioOptions.builder("test-key").autoConnect(false).build(),
                HttpClient.newHttpClient(),
                StreamConfig.builder().voiceId(1).language("en").build(),
                new StreamCallbacks() { @Override public void onChunk(AudioChunk chunk) {} },
                diagnostics);
        FakeWebSocket ws = new FakeWebSocket();
        setField(session, "ws", ws);
        StreamingSession.SessionListener listener = session.new SessionListener();

        session.send("Hello.", true);
        listener.onText(ws, AUDIO_FRAME, true);
        listener.onClose(ws, 1006, "");

        diagnostics.flushNow();
        Map<String, String> attrs = RecordingSender.attributesOf(sender.records().get(0));
        assertEquals("stream_interrupted", attrs.get("kugel.event"));
        assertEquals("receiving_audio", attrs.get("kugel.failure_stage"));
        assertEquals("1006", attrs.get("kugel.ws_close_code"));
        assertEquals("1", attrs.get("kugel.audio_chunks"));
    }

    @Test
    void multiContextTurnsAreIndependentPerContext() throws Exception {
        RecordingSender sender = new RecordingSender();
        Diagnostics diagnostics = reporter(sender);
        MultiContextSession session = new MultiContextSession(
                KugelAudioOptions.builder("test-key").autoConnect(false).build(),
                HttpClient.newHttpClient(),
                MultiContextConfig.builder().build(),
                diagnostics);
        FakeWebSocket ws = new FakeWebSocket();
        setField(session, "ws", ws);
        setField(session, "callbacks", new MultiContextCallbacks() {
            @Override public void onChunk(String contextId, AudioChunk chunk) {}
        });
        MultiContextSession.MultiContextListener listener = session.new MultiContextListener();

        session.send("a", "Hello from a.", true);
        session.send("b", "Hello from b.", true);
        session.send("c", "Hello from c.", true);

        // Context a fails; b and c are untouched by it.
        listener.onText(ws, errorFrame("a", "req_a1"), true);
        // Context c is barged in on.
        session.closeContext("c", true);
        // Context b completes.
        listener.onText(ws, AUDIO_FRAME.replace("{", "{\"context_id\":\"b\","), true);
        listener.onText(ws, "{\"final\":true,\"context_id\":\"b\"}", true);
        // Context a's next turn fails again and is reported again.
        session.send("a", "Again from a.", true);
        listener.onText(ws, errorFrame("a", "req_a2"), true);

        assertEquals(2, diagnostics.failureCount());
        assertEquals(1, diagnostics.successCount());
        assertEquals(1, diagnostics.cancelledCount());

        diagnostics.flushNow();
        List<JsonNode> records = sender.records();
        assertEquals(2, records.size());
        Map<String, String> first = RecordingSender.attributesOf(records.get(0));
        Map<String, String> second = RecordingSender.attributesOf(records.get(1));
        assertEquals("req_a1", first.get("kugel.server_request_id"));
        assertEquals("req_a2", second.get("kugel.server_request_id"));
        assertNotEquals(first.get("kugel.operation_id"), second.get("kugel.operation_id"),
                "each turn is its own operation");
        assertEquals("multi_context", first.get("kugel.operation"));
        assertEquals("request_failed", first.get("kugel.event"));
    }
}
