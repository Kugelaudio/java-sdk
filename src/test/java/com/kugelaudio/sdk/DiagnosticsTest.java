package com.kugelaudio.sdk;

import com.fasterxml.jackson.databind.JsonNode;
import com.kugelaudio.sdk.internal.Diagnostics;
import com.kugelaudio.sdk.internal.DiagnosticsConfig;
import com.kugelaudio.sdk.internal.DiagnosticsEvent;
import com.kugelaudio.sdk.internal.HttpHelper;
import com.kugelaudio.sdk.internal.OtlpEncoder;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers enablement, delivery and encoding from
 * {@code services/ingress/docs/sdk-diagnostics-contract.md}. No test here
 * performs network I/O: every reporter is built with an injected
 * {@link RecordingSender}.
 */
class DiagnosticsTest {

    private static final String HOSTED = "https://api.kugelaudio.com";
    private static final String CUSTOM = "https://tts.acme-internal.example";

    private static UnaryOperator<String> env(Map<String, String> values) {
        return values::get;
    }

    private static final UnaryOperator<String> NO_ENV = env(Map.of());

    private static final String HOSTED_DIAGNOSTICS_URL = HOSTED + "/v1/sdk-diagnostics";

    private static DiagnosticsConfig activeConfig() {
        return DiagnosticsConfig.of(true, HOSTED_DIAGNOSTICS_URL, DiagnosticsConfig.ENDPOINT_HOSTED);
    }

    /** Queues one failure event and delivers it on the calling thread. */
    private static void reportOneFailure(Diagnostics diagnostics) {
        diagnostics.startOperation(Diagnostics.OP_GENERATE, Diagnostics.TRANSPORT_HTTP)
                .stage(Diagnostics.STAGE_CONNECTING)
                .fail(new ConnectionException("nope"));
        diagnostics.flushNow();
    }

    // ── 1. Enablement matrix ────────────────────────────────────────────

    @Test
    void hostedEndpointEnablesTelemetryByDefault() {
        DiagnosticsConfig config = DiagnosticsConfig.resolve(HOSTED, null, NO_ENV);
        assertTrue(config.isEnabled());
        assertEquals(DiagnosticsConfig.ENDPOINT_HOSTED, config.getEndpointKind());
    }

    @Test
    void customEndpointDisablesTelemetryByDefault() {
        DiagnosticsConfig config = DiagnosticsConfig.resolve(CUSTOM, null, NO_ENV);
        assertFalse(config.isEnabled());
        assertEquals(DiagnosticsConfig.ENDPOINT_CUSTOM, config.getEndpointKind());
    }

    @Test
    void explicitOptionBeatsTheDefaultInBothDirections() {
        assertFalse(DiagnosticsConfig.resolve(HOSTED, Boolean.FALSE, NO_ENV).isEnabled());
        assertTrue(DiagnosticsConfig.resolve(CUSTOM, Boolean.TRUE, NO_ENV).isEnabled());
    }

    @Test
    void envBeatsTheExplicitOptionInBothDirections() {
        UnaryOperator<String> off = env(Map.of(DiagnosticsConfig.ENV_TELEMETRY, "off"));
        UnaryOperator<String> on = env(Map.of(DiagnosticsConfig.ENV_TELEMETRY, "1"));
        assertFalse(DiagnosticsConfig.resolve(HOSTED, Boolean.TRUE, off).isEnabled());
        assertTrue(DiagnosticsConfig.resolve(CUSTOM, Boolean.FALSE, on).isEnabled());
    }

    @Test
    void envAcceptsTheDocumentedTokensOnly() {
        for (String truthy : List.of("1", "true", "on", "yes", "TRUE", " Yes ")) {
            assertTrue(
                    DiagnosticsConfig.resolve(CUSTOM, null, env(Map.of(DiagnosticsConfig.ENV_TELEMETRY, truthy)))
                            .isEnabled(),
                    truthy + " should enable");
        }
        for (String falsy : List.of("0", "false", "off", "no")) {
            assertFalse(
                    DiagnosticsConfig.resolve(HOSTED, null, env(Map.of(DiagnosticsConfig.ENV_TELEMETRY, falsy)))
                            .isEnabled(),
                    falsy + " should disable");
        }
        // Unrecognised value falls through to the next rule (hosted default).
        assertTrue(
                DiagnosticsConfig.resolve(HOSTED, null, env(Map.of(DiagnosticsConfig.ENV_TELEMETRY, "maybe")))
                        .isEnabled());
    }

    /**
     * The endpoint is derived from the client's own API URL — the same
     * authenticated host, so no second hostname and no public credential.
     */
    @Test
    void endpointIsDerivedFromTheEffectiveApiUrl() {
        assertEquals(
                "https://api.kugelaudio.com/v1/sdk-diagnostics",
                DiagnosticsConfig.resolve(HOSTED, null, NO_ENV).getDiagnosticsUrl());
        assertEquals(
                "https://tts.acme-internal.example/v1/sdk-diagnostics",
                DiagnosticsConfig.resolve(CUSTOM, Boolean.TRUE, NO_ENV).getDiagnosticsUrl());
        // Trailing slashes and a path prefix on the base URL are handled.
        assertEquals(
                "https://eu.api.kugelaudio.com/v1/sdk-diagnostics",
                DiagnosticsConfig.resolve("https://eu.api.kugelaudio.com//", null, NO_ENV)
                        .getDiagnosticsUrl());
        assertEquals(
                "https://gw.example/kugel/v1/sdk-diagnostics",
                DiagnosticsConfig.resolve("https://gw.example/kugel", Boolean.TRUE, NO_ENV)
                        .getDiagnosticsUrl());
    }

    /** There is no endpoint override: no environment variable can redirect the target. */
    @Test
    void noEnvironmentVariableRedirectsTheTarget() {
        UnaryOperator<String> everythingSet = key ->
                DiagnosticsConfig.ENV_TELEMETRY.equals(key) ? null : "https://collector.example/ingest";
        assertEquals(
                HOSTED_DIAGNOSTICS_URL,
                DiagnosticsConfig.resolve(HOSTED, null, everythingSet).getDiagnosticsUrl());
    }

    /** The public OneUptime ingestion token is gone from the SDK entirely. */
    @Test
    void noIngestionTokenSurvivesInTheConfigSurface() {
        List<String> members = java.util.Arrays.stream(DiagnosticsConfig.class.getDeclaredFields())
                .map(java.lang.reflect.Field::getName)
                .toList();
        assertFalse(members.contains("DEFAULT_TELEMETRY_TOKEN"));
        assertFalse(members.contains("ENV_TELEMETRY_TOKEN"));
        assertFalse(members.contains("TOKEN_HEADER"));
        assertEquals(
                List.of("ENV_TELEMETRY"),
                members.stream().filter(m -> m.startsWith("ENV_")).toList(),
                "KUGELAUDIO_TELEMETRY is the only environment input");
    }

    @Test
    void builderTelemetryOptionReachesTheResolvedConfig() {
        KugelAudioOptions off = KugelAudioOptions.builder("k").telemetry(false).build();
        KugelAudioOptions unset = KugelAudioOptions.builder("k").build();
        assertEquals(Boolean.FALSE, off.getTelemetry());
        assertNull(unset.getTelemetry());
        assertFalse(DiagnosticsConfig.resolve(off.getEffectiveApiUrl(), off.getTelemetry(), NO_ENV).isEnabled());
        assertTrue(DiagnosticsConfig.resolve(unset.getEffectiveApiUrl(), unset.getTelemetry(), NO_ENV).isEnabled());
    }

    // ── 2. Three consecutive 404s switch the reporter off ───────────────

    /**
     * An ingress that predates {@code /v1/sdk-diagnostics} answers 404. After
     * three of those the reporter gives up for the rest of the process rather
     * than pestering it once per batch forever.
     */
    @Test
    void threeConsecutive404sDisableTheReporterPermanently() {
        RecordingSender sender = RecordingSender.responding(404);
        Diagnostics diagnostics = Diagnostics.withSender(activeConfig(), "9.9.9", sender, false);

        for (int i = 0; i < 3; i++) {
            reportOneFailure(diagnostics);
            assertEquals(i + 1, sender.calls.size(), "a 404 is definitive: no retry of that batch");
        }
        assertTrue(diagnostics.isDisabledByNotFound());
        assertFalse(diagnostics.isEnabled(), "the reporter is off for the rest of the process");

        // Nothing more is queued and nothing more is sent, ever.
        reportOneFailure(diagnostics);
        assertEquals(0, diagnostics.queuedCount());
        diagnostics.close();
        assertEquals(3, sender.calls.size(), "no fourth attempt, not even the sdk_stats roll-up");
    }

    @Test
    void twoOf404sAloneDoNotDisableTheReporter() {
        RecordingSender sender = RecordingSender.responding(404);
        Diagnostics diagnostics = Diagnostics.withSender(activeConfig(), "9.9.9", sender, false);

        reportOneFailure(diagnostics);
        reportOneFailure(diagnostics);

        assertFalse(diagnostics.isDisabledByNotFound());
        assertTrue(diagnostics.isEnabled());
        assertEquals(2, sender.calls.size());
    }

    /** "Consecutive" means consecutive: any other response resets the streak. */
    @Test
    void aNon404ResponseResetsThe404Streak() {
        int[] statuses = {404, 404, 500, 500, 404, 404};
        AtomicInteger call = new AtomicInteger();
        Diagnostics.Sender sender =
                (url, headers, body) -> statuses[Math.min(call.getAndIncrement(), statuses.length - 1)];
        Diagnostics diagnostics = Diagnostics.withSender(activeConfig(), "9.9.9", sender, false);

        reportOneFailure(diagnostics);   // 404
        reportOneFailure(diagnostics);   // 404
        reportOneFailure(diagnostics);   // 500, retried once -> consumes two statuses
        reportOneFailure(diagnostics);   // 404
        reportOneFailure(diagnostics);   // 404

        assertFalse(diagnostics.isDisabledByNotFound(), "the 500 broke the streak");
        assertTrue(diagnostics.isEnabled());
    }

    /** Every non-2xx is a silent drop: nothing throws, nothing reaches the caller. */
    @Test
    void nonSuccessResponsesAreSwallowed() {
        for (int status : new int[] {400, 401, 429, 500, 503}) {
            RecordingSender sender = RecordingSender.responding(status);
            Diagnostics diagnostics = Diagnostics.withSender(activeConfig(), "9.9.9", sender, false);
            assertDoesNotThrow(() -> reportOneFailure(diagnostics), "status " + status);
            assertEquals(2, sender.calls.size(), status + ": one attempt plus at most one retry");
            assertFalse(diagnostics.isDisabledByNotFound(), status + " must not disable the reporter");
            assertDoesNotThrow(diagnostics::close);
        }
    }

    /** A 413 is deterministic: the same bytes would be rejected again. */
    @Test
    void a413IsNeverRetried() {
        RecordingSender sender = RecordingSender.responding(413);
        Diagnostics diagnostics = Diagnostics.withSender(activeConfig(), "9.9.9", sender, false);
        assertDoesNotThrow(() -> reportOneFailure(diagnostics));
        assertEquals(1, sender.calls.size(), "413 must not be retried");
        assertFalse(diagnostics.isDisabledByNotFound());
    }

    // ── Batch cap: at most 8 records per POST ───────────────────────────

    @Test
    void aFullQueueIsDrainedInPostsOfAtMostEightRecords() {
        RecordingSender sender = new RecordingSender();
        Diagnostics diagnostics = Diagnostics.withSender(activeConfig(), "2.4.0", sender, false);
        for (int i = 0; i < 20; i++) {
            diagnostics.startOperation(Diagnostics.OP_GENERATE, Diagnostics.TRANSPORT_HTTP)
                    .stage(Diagnostics.STAGE_CONNECTING)
                    .fail(new ConnectionException("x"));
        }

        diagnostics.flushNow();

        List<Integer> sizes = sender.calls.stream()
                .map(call -> RecordingSender.recordsOf(call).size())
                .toList();
        assertEquals(List.of(8, 8, 4), sizes);
        assertEquals(0, diagnostics.queuedCount());
    }

    /** A 413 on one batch neither retries it nor blocks the next batch. */
    @Test
    void a413DropsOnlyItsOwnBatch() {
        RecordingSender sender = RecordingSender.responding(413);
        Diagnostics diagnostics = Diagnostics.withSender(activeConfig(), "2.4.0", sender, false);
        for (int i = 0; i < 16; i++) {
            diagnostics.startOperation(Diagnostics.OP_GENERATE, Diagnostics.TRANSPORT_HTTP)
                    .stage(Diagnostics.STAGE_CONNECTING)
                    .fail(new ConnectionException("x"));
        }
        diagnostics.flushNow();
        assertEquals(2, sender.calls.size(), "two batches of 8, one attempt each");
    }

    // ── close(): bounded, off the caller's thread ───────────────────────

    /**
     * close() must never do network I/O on the caller's thread and must return
     * within about 1 s even when delivery hangs. Covers the path where nothing
     * had failed yet, so no background worker existed before close().
     */
    @Test
    void closeReturnsWithinOneSecondWhenTheSenderHangs() throws Exception {
        java.util.concurrent.CompletableFuture<Thread> senderThread =
                new java.util.concurrent.CompletableFuture<>();
        Diagnostics.Sender hanging = (url, headers, body) -> {
            senderThread.complete(Thread.currentThread());
            Thread.sleep(5_000);
            return 202;
        };
        Diagnostics diagnostics = Diagnostics.withSender(activeConfig(), "2.4.0", hanging, false);
        diagnostics.startOperation(Diagnostics.OP_GENERATE, Diagnostics.TRANSPORT_HTTP).success();

        long started = System.nanoTime();
        diagnostics.close();
        long elapsedMs = (System.nanoTime() - started) / 1_000_000L;

        assertTrue(elapsedMs <= 1_100, "close() blocked for " + elapsedMs + " ms");
        Thread worker = senderThread.get(1, java.util.concurrent.TimeUnit.SECONDS);
        assertNotSame(Thread.currentThread(), worker, "no network I/O on the caller's thread");
        assertTrue(worker.isDaemon(), "delivery must never keep the JVM alive");
    }

    /** A servlet container's webapp classloader must not be pinned by our worker thread. */
    @Test
    void workerThreadDoesNotInheritTheCallersContextClassLoader() throws Exception {
        java.util.concurrent.CompletableFuture<ClassLoader> seen = new java.util.concurrent.CompletableFuture<>();
        Diagnostics.Sender probe = (url, headers, body) -> {
            seen.complete(Thread.currentThread().getContextClassLoader());
            return 202;
        };
        Thread caller = Thread.currentThread();
        ClassLoader original = caller.getContextClassLoader();
        caller.setContextClassLoader(new ClassLoader(original) {});
        try {
            Diagnostics diagnostics = Diagnostics.withSender(activeConfig(), "2.4.0", probe, true);
            diagnostics.startOperation(Diagnostics.OP_GENERATE, Diagnostics.TRANSPORT_HTTP)
                    .stage(Diagnostics.STAGE_CONNECTING)
                    .fail(new ConnectionException("x"));
            diagnostics.close();
        } finally {
            caller.setContextClassLoader(original);
        }
        assertNull(seen.get(1, java.util.concurrent.TimeUnit.SECONDS));
    }

    @Test
    void closeWithNothingToReportSendsNothing() {
        RecordingSender sender = new RecordingSender();
        Diagnostics diagnostics = Diagnostics.withSender(activeConfig(), "2.4.0", sender, false);
        diagnostics.close();
        assertTrue(sender.calls.isEmpty());
    }

    // ── 3. Encoder shape ────────────────────────────────────────────────

    @Test
    void encoderEmitsTheContractResourceAndScopeShape() {
        RecordingSender sender = new RecordingSender();
        Diagnostics diagnostics = Diagnostics.withSender(activeConfig(), "2.4.0", sender, false);

        diagnostics.startOperation(Diagnostics.OP_GENERATE, Diagnostics.TRANSPORT_WEBSOCKET)
                .stage(Diagnostics.STAGE_HANDSHAKE)
                .fail(new ConnectionException("boom"));
        diagnostics.flushNow();

        RecordingSender.Call call = sender.calls.get(0);
        assertEquals(HOSTED_DIAGNOSTICS_URL, call.url());
        assertEquals("application/json", call.headers().get("Content-Type"));
        assertFalse(call.headers().containsKey("x-oneuptime-token"),
                "the public ingestion token header is gone");

        JsonNode resource = call.json().path("resourceLogs").get(0).path("resource");
        Map<String, String> resourceAttrs = new LinkedHashMap<>();
        for (JsonNode attr : resource.path("attributes")) {
            resourceAttrs.put(attr.path("key").asText(), attr.path("value").path("stringValue").asText());
        }
        assertEquals("kugelaudio-sdk", resourceAttrs.get("service.name"));
        assertEquals("2.4.0", resourceAttrs.get("service.version"));
        assertEquals("java", resourceAttrs.get("telemetry.sdk.language"));

        JsonNode scopeLog = call.json().path("resourceLogs").get(0).path("scopeLogs").get(0);
        assertEquals("kugelaudio.diagnostics", scopeLog.path("scope").path("name").asText());
        assertEquals("1", scopeLog.path("scope").path("version").asText());

        JsonNode record = sender.records().get(0);
        assertEquals(17, record.path("severityNumber").asInt());
        assertEquals("ERROR", record.path("severityText").asText());
        assertEquals("connection_failed", record.path("body").path("stringValue").asText());
        assertTrue(record.path("timeUnixNano").isTextual(), "int64 fields are JSON strings");
        assertTrue(record.path("timeUnixNano").asText().matches("\\d{16,}"));

        Map<String, String> attrs = RecordingSender.attributesOf(record);
        assertEquals("connection_failed", attrs.get("kugel.event"));
        assertTrue(attrs.get("kugel.event_id").matches("[0-9a-f]{32}"));
        assertTrue(attrs.get("kugel.operation_id").matches("[0-9a-f]{32}"));
        assertEquals("generate", attrs.get("kugel.operation"));
        assertEquals("java", attrs.get("kugel.sdk.name"));
        assertEquals("2.4.0", attrs.get("kugel.sdk.version"));
        assertTrue(attrs.get("kugel.runtime").matches("java/\\d+\\.\\d+\\.\\d+"), attrs.get("kugel.runtime"));
        assertEquals("none", attrs.get("kugel.integration"));
        assertEquals("websocket", attrs.get("kugel.transport"));
        assertEquals("handshake", attrs.get("kugel.failure_stage"));
        assertEquals("ConnectionException", attrs.get("kugel.error_type"));
        assertEquals("failed", attrs.get("kugel.outcome"));
        assertEquals("hosted", attrs.get("kugel.endpoint_kind"));
        assertEquals("0", attrs.get("kugel.audio_chunks"));
        assertEquals("0", attrs.get("kugel.retry_count"));

        // Ints are encoded as intValue, not stringValue.
        for (JsonNode attr : record.path("attributes")) {
            if ("kugel.elapsed_ms".equals(attr.path("key").asText())) {
                assertTrue(attr.path("value").has("intValue"));
                assertTrue(attr.path("value").path("intValue").isTextual());
            }
        }
    }

    // ── 4. Attribute allowlist ──────────────────────────────────────────

    @Test
    void encoderDropsAttributesOutsideTheAllowlist() {
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("kugel.event", Diagnostics.EVENT_REQUEST_FAILED);
        attrs.put("kugel.event_id", "0".repeat(32));
        attrs.put("kugel.operation_id", "1".repeat(32));
        attrs.put("kugel.sdk.name", "java");
        attrs.put("kugel.sdk.version", "2.4.0");
        // None of these may ever reach the wire.
        attrs.put("kugel.text", "Guten Tag, mein IBAN lautet DE02120300000000202051.");
        attrs.put("api_key", "sk-live-supersecret");
        attrs.put("kugel.url", "https://tenant.internal.example/ws/tts");
        attrs.put("kugel.error_message", "AuthenticationException: bad key for user 42");
        attrs.put("kugel.http_status", 401);

        String payload = new String(
                OtlpEncoder.encode("2.4.0", List.of(new DiagnosticsEvent("request_failed", attrs))),
                StandardCharsets.UTF_8);

        assertFalse(OtlpEncoder.isAllowed("kugel.text"));
        assertTrue(OtlpEncoder.isAllowed("kugel.cancelled_count"), "sdk_stats carries it");
        assertFalse(payload.contains("kugel.text"));
        assertFalse(payload.contains("api_key"));
        assertFalse(payload.contains("kugel.url"));
        assertFalse(payload.contains("kugel.error_message"));
        assertFalse(payload.contains("DE02120300000000202051"));
        assertFalse(payload.contains("sk-live-supersecret"));
        assertFalse(payload.contains("tenant.internal.example"));
        assertFalse(payload.contains("bad key for user 42"));
        // The allowed neighbours survive.
        assertTrue(payload.contains("kugel.event_id"));
        assertTrue(payload.contains("\"intValue\":\"401\""));
    }

    @Test
    void encoderDropsTypeMismatchedValues() {
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("kugel.http_status", "four-oh-one");
        attrs.put("kugel.outcome", 42);
        String payload = new String(
                OtlpEncoder.encode("2.4.0", List.of(new DiagnosticsEvent("request_failed", attrs))),
                StandardCharsets.UTF_8);
        assertFalse(payload.contains("four-oh-one"));
        assertFalse(payload.contains("kugel.outcome"));
    }

    // ── 5. Queue bound ──────────────────────────────────────────────────

    @Test
    void queueKeepsTheNewest64EventsAndDropsTheOldest() {
        RecordingSender sender = new RecordingSender();
        Diagnostics diagnostics = Diagnostics.withSender(activeConfig(), "2.4.0", sender, false);

        for (int i = 0; i < 100; i++) {
            Map<String, Object> attrs = new LinkedHashMap<>();
            attrs.put("kugel.event", Diagnostics.EVENT_REQUEST_FAILED);
            attrs.put("kugel.operation_id", String.format("%032d", i));
            diagnostics.record(new DiagnosticsEvent("request_failed", attrs));
        }
        assertEquals(Diagnostics.QUEUE_CAPACITY, diagnostics.queuedCount());

        diagnostics.flushNow();
        assertEquals(8, sender.calls.size(), "64 records leave as 8 POSTs of 8");
        List<JsonNode> records = sender.allRecords();
        assertEquals(64, records.size());
        assertEquals(
                String.format("%032d", 36),
                RecordingSender.attributesOf(records.get(0)).get("kugel.operation_id"),
                "events 0..35 must have been dropped, oldest first");
        assertEquals(
                String.format("%032d", 99),
                RecordingSender.attributesOf(records.get(63)).get("kugel.operation_id"));
    }

    // ── 6. A failing sender is invisible to the caller ──────────────────

    @Test
    void failingSenderNeverRaisesIntoTheCaller() {
        RecordingSender sender = RecordingSender.alwaysFailing();
        Diagnostics diagnostics = Diagnostics.withSender(activeConfig(), "2.4.0", sender, false);

        assertDoesNotThrow(() -> {
            diagnostics.startOperation(Diagnostics.OP_STREAM, Diagnostics.TRANSPORT_WEBSOCKET)
                    .stage(Diagnostics.STAGE_RECEIVING_AUDIO)
                    .fail(new ConnectionException("socket died"));
            diagnostics.flushNow();
        });
        assertEquals(2, sender.calls.size(), "one delivery attempt plus at most one retry");
        assertDoesNotThrow(diagnostics::close);
    }

    /**
     * A throwing sender and an encoder that blows up mid-record, both on the
     * background worker: nothing reaches the caller and nothing is printed —
     * no log line above DEBUG, no stack trace, no "Exception in thread".
     */
    @Test
    void throwingSenderOrEncoderPrintsNothing() throws Exception {
        java.io.PrintStream originalOut = System.out;
        java.io.PrintStream originalErr = System.err;
        java.io.ByteArrayOutputStream captured = new java.io.ByteArrayOutputStream();
        java.io.PrintStream capture = new java.io.PrintStream(captured, true, StandardCharsets.UTF_8);
        System.setOut(capture);
        System.setErr(capture);
        try {
            Diagnostics throwingSender = Diagnostics.withSender(
                    activeConfig(), "2.4.0", RecordingSender.alwaysFailing(), true);
            for (int i = 0; i < 10; i++) {
                throwingSender.startOperation(Diagnostics.OP_GENERATE, Diagnostics.TRANSPORT_HTTP)
                        .stage(Diagnostics.STAGE_CONNECTING)
                        .fail(new ConnectionException("x"));
            }
            Diagnostics throwingEncoder = Diagnostics.withSender(
                    activeConfig(), "2.4.0", new RecordingSender(), true);
            Map<String, Object> poisoned = new LinkedHashMap<>();
            poisoned.put("kugel.operation", new Object() {
                @Override public String toString() { throw new IllegalStateException("encoder on fire"); }
            });
            for (int i = 0; i < 10; i++) {
                throwingEncoder.record(new DiagnosticsEvent(Diagnostics.EVENT_REQUEST_FAILED, poisoned));
            }
            assertDoesNotThrow(throwingSender::close);
            assertDoesNotThrow(throwingEncoder::close);
            Thread.sleep(200); // let any stray background output land
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
        assertEquals("", captured.toString(StandardCharsets.UTF_8));
    }

    // ── 8. Cancellation ─────────────────────────────────────────────────

    /**
     * A caller-initiated cancellation is counted, never reported individually.
     * Emitting one ERROR-severity record per barge-in would drown a voice agent
     * in telemetry for something that is not a fault.
     */
    @Test
    void cancellationEmitsNoEventAndIsNotAFailure() {
        RecordingSender sender = new RecordingSender();
        Diagnostics diagnostics = Diagnostics.withSender(activeConfig(), "2.4.0", sender, false);

        Diagnostics.Operation op = diagnostics
                .startOperation(Diagnostics.OP_STREAM_SESSION, Diagnostics.TRANSPORT_WEBSOCKET)
                .stage(Diagnostics.STAGE_RECEIVING_AUDIO);
        op.chunk(512);
        op.cancelled();
        // A cancelled operation is terminal: a later failure adds nothing.
        op.fail(new ConnectionException("late"));
        diagnostics.flushNow();

        assertEquals(0, diagnostics.queuedCount(), "a cancellation queues zero events");
        assertTrue(sender.calls.isEmpty(), "a cancellation must not reach the wire");
        assertEquals(1, diagnostics.cancelledCount(), "the cancellation is counted");
        assertEquals(0, diagnostics.failureCount(), "cancellations do not count as failures");
        assertEquals(0, diagnostics.successCount(), "a cancellation is not a success either");
    }

    /** Repeated barge-ins stay silent, and every one of them still lands in sdk_stats. */
    @Test
    void repeatedCancellationsStaySilentButAreAllCounted() {
        RecordingSender sender = new RecordingSender();
        Diagnostics diagnostics = Diagnostics.withSender(activeConfig(), "2.4.0", sender, false);

        for (int i = 0; i < 20; i++) {
            diagnostics
                    .startOperation(Diagnostics.OP_STREAM_SESSION, Diagnostics.TRANSPORT_WEBSOCKET)
                    .stage(Diagnostics.STAGE_RECEIVING_AUDIO)
                    .cancelled();
        }
        diagnostics.flushNow();
        assertTrue(sender.calls.isEmpty(), "20 barge-ins produce no delivery");

        diagnostics.close();
        List<JsonNode> records = sender.records();
        assertEquals(1, records.size(), "only the sdk_stats roll-up is ever sent");
        Map<String, String> stats = RecordingSender.attributesOf(records.get(0));
        assertEquals("sdk_stats", stats.get("kugel.event"));
        assertEquals(9, records.get(0).path("severityNumber").asInt(), "sdk_stats is INFO, not a fault");
        assertEquals("INFO", records.get(0).path("severityText").asText());
        assertEquals("20", stats.get("kugel.cancelled_count"));
        assertEquals("0", stats.get("kugel.failure_count"));
    }

    @Test
    void sdkStatsCountsSuccessesFailuresAndCancellationsOnClose() {
        RecordingSender sender = new RecordingSender();
        Diagnostics diagnostics = Diagnostics.withSender(activeConfig(), "2.4.0", sender, false);

        diagnostics.startOperation(Diagnostics.OP_GENERATE, Diagnostics.TRANSPORT_HTTP).success();
        diagnostics.startOperation(Diagnostics.OP_GENERATE, Diagnostics.TRANSPORT_HTTP).success();
        diagnostics.startOperation(Diagnostics.OP_GENERATE, Diagnostics.TRANSPORT_HTTP)
                .stage(Diagnostics.STAGE_SENDING_REQUEST)
                .fail(new ValidationException("bad"));
        diagnostics.startOperation(Diagnostics.OP_STREAM, Diagnostics.TRANSPORT_WEBSOCKET).cancelled();

        assertEquals(2, diagnostics.successCount());
        assertEquals(1, diagnostics.failureCount());
        assertEquals(1, diagnostics.cancelledCount());

        diagnostics.close();
        List<JsonNode> records = sender.records();
        assertEquals(2, records.size(), "the failure and the stats roll-up, not the cancellation");
        Map<String, String> stats =
                RecordingSender.attributesOf(records.get(records.size() - 1));
        assertEquals("sdk_stats", stats.get("kugel.event"));
        assertEquals("2", stats.get("kugel.success_count"));
        assertEquals("1", stats.get("kugel.failure_count"));
        assertEquals("1", stats.get("kugel.cancelled_count"));
    }

    // ── Event-name derivation ───────────────────────────────────────────

    @Test
    void eventNameIsDerivedFromHowFarTheOperationGot() {
        RecordingSender sender = new RecordingSender();
        Diagnostics diagnostics = Diagnostics.withSender(activeConfig(), "2.4.0", sender, false);

        diagnostics.startOperation(Diagnostics.OP_STREAM, Diagnostics.TRANSPORT_WEBSOCKET)
                .stage(Diagnostics.STAGE_CONNECTING).fail(new ConnectionException("a"));
        diagnostics.startOperation(Diagnostics.OP_VOICES, Diagnostics.TRANSPORT_HTTP)
                .stage(Diagnostics.STAGE_SENDING_REQUEST).fail(new ValidationException("b"));
        diagnostics.startOperation(Diagnostics.OP_STREAM, Diagnostics.TRANSPORT_WEBSOCKET)
                .stage(Diagnostics.STAGE_RECEIVING_AUDIO).fail(new ConnectionException("c"));
        Diagnostics.Operation retried = diagnostics
                .startOperation(Diagnostics.OP_STREAM, Diagnostics.TRANSPORT_WEBSOCKET)
                .stage(Diagnostics.STAGE_CONNECTING);
        retried.retry();
        retried.fail(new ConnectionException("d"));

        diagnostics.flushNow();
        List<String> events = sender.records().stream()
                .map(r -> r.path("body").path("stringValue").asText())
                .toList();
        assertEquals(
                List.of("connection_failed", "request_failed", "stream_interrupted", "retry_exhausted"),
                events);
    }

    // ── Auth: the SDK's own headers, not a second scheme ────────────────

    @Test
    void diagnosticsPostCarriesTheSdkAuthHeaders() {
        KugelAudioOptions options = KugelAudioOptions.builder("eu-ka_secret").build();
        RecordingSender sender = new RecordingSender();
        Diagnostics diagnostics = Diagnostics.withSender(
                activeConfig(), "2.4.0", sender, false, HttpHelper.authHeaders(options));

        reportOneFailure(diagnostics);

        Map<String, String> headers = sender.calls.get(0).headers();
        assertEquals("application/json", headers.get("Content-Type"));
        // Byte-for-byte what HttpHelper puts on every other /v1 call.
        assertEquals(HttpHelper.authHeaders(options).get("Authorization"), headers.get("Authorization"));
        assertEquals(HttpHelper.authHeaders(options).get("X-API-Key"), headers.get("X-API-Key"));
        assertEquals("Bearer ka_secret", headers.get("Authorization"), "region prefix already stripped");
        assertEquals("ka_secret", headers.get("X-API-Key"));
        assertEquals(3, headers.size(), "content type plus the two auth headers, nothing else");
    }

    /** Content-Type is set last, so no header the SDK was given can override it. */
    @Test
    void contentTypeIsSetLastAndCannotBeOverridden() {
        RecordingSender sender = new RecordingSender();
        Diagnostics diagnostics = Diagnostics.withSender(
                activeConfig(), "2.4.0", sender, false,
                Map.of("Authorization", "Bearer k", "content-type", "text/plain"));

        reportOneFailure(diagnostics);

        Map<String, String> headers = sender.calls.get(0).headers();
        assertEquals("application/json", headers.get("Content-Type"));
        assertFalse(headers.containsKey("content-type"));
    }

    @Test
    void disabledReporterRecordsNothing() {
        Diagnostics diagnostics = Diagnostics.disabled();
        diagnostics.startOperation(Diagnostics.OP_GENERATE, Diagnostics.TRANSPORT_HTTP)
                .fail(new ConnectionException("x"));
        assertEquals(0, diagnostics.queuedCount());
        assertDoesNotThrow(diagnostics::close);
    }
}
