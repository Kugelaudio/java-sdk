package com.kugelaudio.sdk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kugelaudio.sdk.internal.Diagnostics;
import com.kugelaudio.sdk.internal.DiagnosticsEvent;
import com.kugelaudio.sdk.internal.OtlpEncoder;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;

/**
 * "Golden batches" from {@code services/ingress/docs/sdk-diagnostics-contract.md}:
 * one deterministic batch, produced by this SDK's own {@link OtlpEncoder}, with
 * one record per event type and every allowlisted attribute across them. The
 * ingress contract test loads the committed file and checks it against the
 * server allowlist, so encoder drift fails CI on both sides.
 *
 * <p>Regenerate after an intended encoder change, then commit the file:
 * <pre>{@code
 * mvn -B test -Dtest=DiagnosticsGoldenBatchTest -DupdateGolden=true
 * }</pre>
 */
class DiagnosticsGoldenBatchTest {

    static final Path GOLDEN = Path.of("src/test/resources/diagnostics_golden_batch.json");

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long T0 = 1_767_225_600_000_000_000L; // 2026-01-01T00:00:00Z

    private static Map<String, Object> base(String event, int n, String operationId) {
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("kugel.event", event);
        attrs.put("kugel.event_id", String.format("%032x", 0xe0 + n));
        attrs.put("kugel.operation_id", operationId);
        return attrs;
    }

    private static void identity(Map<String, Object> attrs) {
        attrs.put("kugel.sdk.name", "java");
        attrs.put("kugel.sdk.version", "2.4.0");
        attrs.put("kugel.runtime", "java/17.0.2");
        attrs.put("kugel.integration", "none");
    }

    /** Same key order as {@code Diagnostics.Operation}'s failure attributes. */
    private static DiagnosticsEvent failure(
            int n, String event, String operation, String transport, String stage,
            String errorType, String errorCode, Integer httpStatus, Integer wsCloseCode,
            String requestId, long elapsedMs, int chunks, long bytes, int retries) {
        Map<String, Object> attrs = base(event, n, String.format("%032x", 0xa0 + n));
        attrs.put("kugel.operation", operation);
        identity(attrs);
        attrs.put("kugel.transport", transport);
        attrs.put("kugel.failure_stage", stage);
        attrs.put("kugel.error_type", errorType);
        if (errorCode != null) attrs.put("kugel.error_code", errorCode);
        if (httpStatus != null) attrs.put("kugel.http_status", httpStatus);
        if (wsCloseCode != null) attrs.put("kugel.ws_close_code", wsCloseCode);
        if (requestId != null) attrs.put("kugel.server_request_id", requestId);
        attrs.put("kugel.elapsed_ms", elapsedMs);
        attrs.put("kugel.audio_chunks", chunks);
        attrs.put("kugel.audio_bytes", bytes);
        attrs.put("kugel.retry_count", retries);
        attrs.put("kugel.outcome", Diagnostics.OUTCOME_FAILED);
        attrs.put("kugel.endpoint_kind", "hosted");
        return new DiagnosticsEvent(event, T0 + n, attrs);
    }

    static List<DiagnosticsEvent> goldenEvents() {
        Map<String, Object> stats = base(Diagnostics.EVENT_SDK_STATS, 5, String.format("%032x", 0xa5));
        identity(stats);
        stats.put("kugel.endpoint_kind", "hosted");
        stats.put("kugel.success_count", 41);
        stats.put("kugel.failure_count", 4);
        stats.put("kugel.cancelled_count", 7);
        return List.of(
                failure(1, Diagnostics.EVENT_CONNECTION_FAILED, Diagnostics.OP_STREAM_SESSION,
                        Diagnostics.TRANSPORT_WEBSOCKET, Diagnostics.STAGE_HANDSHAKE,
                        "AuthenticationException", "UNAUTHORIZED", 401, null,
                        "3f2a9c1d0b8e4f6a9c1d0b8e4f6a3f2a", 184L, 0, 0L, 0),
                failure(2, Diagnostics.EVENT_REQUEST_FAILED, Diagnostics.OP_VOICES,
                        Diagnostics.TRANSPORT_HTTP, Diagnostics.STAGE_SENDING_REQUEST,
                        "ValidationException", "VALIDATION_ERROR", 400, null,
                        "req_7Hq2mZ", 96L, 0, 0L, 0),
                failure(3, Diagnostics.EVENT_STREAM_INTERRUPTED, Diagnostics.OP_MULTI_CONTEXT,
                        Diagnostics.TRANSPORT_WEBSOCKET, Diagnostics.STAGE_RECEIVING_AUDIO,
                        "ConnectionException", null, null, 1006,
                        "b41c0e7d2a954f13a8e6c2d9f0b17e44", 2317L, 12, 46080L, 0),
                failure(4, Diagnostics.EVENT_RETRY_EXHAUSTED, Diagnostics.OP_GENERATE,
                        Diagnostics.TRANSPORT_WEBSOCKET, Diagnostics.STAGE_AWAITING_FIRST_AUDIO,
                        "ConnectionException", null, null, 4500,
                        "c9d8e7f6a5b44c3d2e1f0a9b8c7d6e5f", 6021L, 0, 0L, 1),
                new DiagnosticsEvent(Diagnostics.EVENT_SDK_STATS, T0 + 5, stats));
    }

    private static String render() throws Exception {
        JsonNode tree = MAPPER.readTree(OtlpEncoder.encode("2.4.0", goldenEvents()));
        return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(tree) + "\n";
    }

    @Test
    void encoderReproducesTheCommittedGoldenBatch() throws Exception {
        String rendered = render();
        if (Boolean.getBoolean("updateGolden")) {
            Files.writeString(GOLDEN, rendered, StandardCharsets.UTF_8);
        }
        assertTrue(Files.exists(GOLDEN), "missing " + GOLDEN + "; regenerate with -DupdateGolden=true");
        assertEquals(
                Files.readString(GOLDEN, StandardCharsets.UTF_8),
                rendered,
                "encoder output drifted from the golden batch; if intended, regenerate with "
                        + "-DupdateGolden=true and commit the file");
    }

    @Test
    void goldenBatchHasOneRecordPerEventAndCoversTheWholeAllowlist() throws Exception {
        JsonNode records = MAPPER.readTree(Files.readString(GOLDEN, StandardCharsets.UTF_8))
                .path("resourceLogs").get(0).path("scopeLogs").get(0).path("logRecords");
        List<String> events = new java.util.ArrayList<>();
        Set<String> keys = new TreeSet<>();
        for (JsonNode record : records) {
            events.add(record.path("body").path("stringValue").asText());
            record.path("attributes").forEach(a -> keys.add(a.path("key").asText()));
        }
        assertEquals(
                List.of("connection_failed", "request_failed", "stream_interrupted",
                        "retry_exhausted", "sdk_stats"),
                events);
        assertEquals(new TreeSet<>(OtlpEncoder.allowedKeys()), keys,
                "together the records carry every allowlisted key and nothing else");
        assertTrue(records.size() <= Diagnostics.MAX_RECORDS_PER_POST);
    }
}
