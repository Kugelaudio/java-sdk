package com.kugelaudio.sdk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kugelaudio.sdk.internal.Diagnostics;
import com.kugelaudio.sdk.internal.DiagnosticsConfig;
import com.kugelaudio.sdk.internal.Errors;
import com.kugelaudio.sdk.internal.HttpHelper;
import com.kugelaudio.sdk.internal.SdkMetadata;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Request-ID correlation and event classification from
 * {@code services/ingress/docs/sdk-diagnostics-contract.md}, against the SDK's
 * real error paths: a mocked HTTP 401, a WebSocket close 1006, a server error
 * frame and a rejected WebSocket handshake. The telemetry sender is always a
 * fake; the handshake test talks to a loopback socket only.
 */
class DiagnosticsErrorPathTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static Diagnostics reporter(RecordingSender sender) {
        return Diagnostics.withSender(
                DiagnosticsConfig.of(
                        true,
                        "https://api.kugelaudio.com/v1/sdk-diagnostics",
                        DiagnosticsConfig.ENDPOINT_HOSTED),
                "2.4.0",
                sender,
                false);
    }

    // ── 7 / 9: HTTP 401 ─────────────────────────────────────────────────

    @Test
    void http401ProducesOneEventAndCarriesTheRequestId() {
        RecordingSender sender = new RecordingSender();
        Diagnostics diagnostics = reporter(sender);
        FakeHttpClient client = FakeHttpClient.responding(
                401,
                "{\"error\":\"nope\",\"error_code\":\"UNAUTHORIZED\"}",
                Map.of("x-request-id", List.of("req_abc123")));
        HttpHelper helper = new HttpHelper(
                client, KugelAudioOptions.builder("test-key").build(), diagnostics);

        AuthenticationException thrown = assertThrows(
                AuthenticationException.class, () -> helper.get("models", String.class));

        // A3: the typed error carries the server's correlation id.
        assertEquals("req_abc123", thrown.getRequestId());

        diagnostics.flushNow();
        List<JsonNode> records = sender.records();
        assertEquals(1, records.size(), "exactly one event per failed operation");

        Map<String, String> attrs = RecordingSender.attributesOf(records.get(0));
        assertEquals("request_failed", attrs.get("kugel.event"));
        assertEquals("sending_request", attrs.get("kugel.failure_stage"));
        assertEquals("AuthenticationException", attrs.get("kugel.error_type"));
        assertEquals("UNAUTHORIZED", attrs.get("kugel.error_code"));
        assertEquals("401", attrs.get("kugel.http_status"));
        assertEquals("req_abc123", attrs.get("kugel.server_request_id"));
        assertEquals("models", attrs.get("kugel.operation"));
        assertEquals("http", attrs.get("kugel.transport"));
        assertEquals("failed", attrs.get("kugel.outcome"));
        assertEquals(1, diagnostics.failureCount());
    }

    @Test
    void unreachableHostReportsConnectionFailedAtTheConnectingStage() {
        RecordingSender sender = new RecordingSender();
        Diagnostics diagnostics = reporter(sender);
        HttpHelper helper = new HttpHelper(
                FakeHttpClient.failing(new IOException("connection refused")),
                KugelAudioOptions.builder("test-key").build(),
                diagnostics);

        assertThrows(ConnectionException.class, () -> helper.get("voices", String.class));

        diagnostics.flushNow();
        Map<String, String> attrs = RecordingSender.attributesOf(sender.records().get(0));
        assertEquals("connection_failed", attrs.get("kugel.event"));
        assertEquals("connecting", attrs.get("kugel.failure_stage"));
        assertEquals("ConnectionException", attrs.get("kugel.error_type"));
        assertEquals("voices", attrs.get("kugel.operation"));
        assertFalse(attrs.containsKey("kugel.server_request_id"));
    }

    @Test
    void successfulRequestReportsNoEvent() {
        RecordingSender sender = new RecordingSender();
        Diagnostics diagnostics = reporter(sender);
        HttpHelper helper = new HttpHelper(
                FakeHttpClient.responding(200, "\"ok\"", Map.of()),
                KugelAudioOptions.builder("test-key").build(),
                diagnostics);

        assertEquals("ok", helper.get("models", String.class));
        diagnostics.flushNow();
        assertEquals(0, sender.calls.size());
        assertEquals(1, diagnostics.successCount());
    }

    // ── 7: WebSocket close 1006 ─────────────────────────────────────────

    @Test
    void websocketClose1006ProducesOneStreamInterruptedEvent() {
        RecordingSender sender = new RecordingSender();
        Diagnostics diagnostics = reporter(sender);
        Diagnostics.Operation op = diagnostics
                .startOperation(Diagnostics.OP_STREAM, Diagnostics.TRANSPORT_WEBSOCKET)
                .stage(Diagnostics.STAGE_AWAITING_FIRST_AUDIO);

        TTSResource.StreamListener listener = new TTSResource.StreamListener(
                new StreamCallbacks() {
                    @Override public void onChunk(AudioChunk chunk) {}
                },
                op);
        // Abnormal closure with no audio received: the stream broke.
        listener.onClose(null, 1006, "");
        // A second close must not add a second event.
        listener.onClose(null, 1006, "");

        diagnostics.flushNow();
        List<JsonNode> records = sender.records();
        assertEquals(1, records.size());
        Map<String, String> attrs = RecordingSender.attributesOf(records.get(0));
        assertEquals("stream_interrupted", attrs.get("kugel.event"));
        assertEquals("awaiting_first_audio", attrs.get("kugel.failure_stage"));
        assertEquals("ConnectionException", attrs.get("kugel.error_type"));
        assertEquals("1006", attrs.get("kugel.ws_close_code"));
        assertEquals("websocket", attrs.get("kugel.transport"));
    }

    // ── 9: WebSocket error frame ────────────────────────────────────────

    @Test
    void websocketErrorFrameCarriesTheRequestIdOntoTheTypedError() throws Exception {
        JsonNode frame = MAPPER.readTree(
                "{\"error\":\"rate limited\",\"error_code\":\"RATE_LIMITED\","
                        + "\"request_id\":\"ws_req_42\",\"retry_after\":3}");
        KugelAudioException ex = Errors.classifyWsFrame(frame);

        assertInstanceOf(RateLimitException.class, ex);
        assertEquals("ws_req_42", ex.getRequestId());
        assertEquals(Integer.valueOf(3), ex.getRetryAfter());
        assertTrue(ex.getMessage().contains("ws_req_42"));
    }

    @Test
    void websocketErrorFrameWithoutRequestIdStaysNull() throws Exception {
        JsonNode frame = MAPPER.readTree("{\"error\":\"boom\",\"error_code\":\"INTERNAL_ERROR\"}");
        assertNull(Errors.classifyWsFrame(frame).getRequestId());
    }

    @Test
    void websocketErrorFrameProducesOneEventWithTheServerRequestId() {
        RecordingSender sender = new RecordingSender();
        Diagnostics diagnostics = reporter(sender);
        Diagnostics.Operation op = diagnostics
                .startOperation(Diagnostics.OP_GENERATE, Diagnostics.TRANSPORT_WEBSOCKET)
                .stage(Diagnostics.STAGE_AWAITING_FIRST_AUDIO);

        TTSResource.RequestHandler handler = new TTSResource.RequestHandler(
                new StreamCallbacks() {
                    @Override public void onChunk(AudioChunk chunk) {}
                },
                op);
        TTSResource.processMessage(
                "{\"error\":\"nope\",\"error_code\":\"UNAUTHORIZED\",\"request_id\":\"ws_req_7\"}",
                handler);

        assertTrue(handler.completion.isCompletedExceptionally());

        diagnostics.flushNow();
        List<JsonNode> records = sender.records();
        assertEquals(1, records.size());
        Map<String, String> attrs = RecordingSender.attributesOf(records.get(0));
        // Contract "Event classification": a server error frame is request_failed.
        assertEquals("request_failed", attrs.get("kugel.event"));
        assertEquals("awaiting_first_audio", attrs.get("kugel.failure_stage"));
        assertEquals("AuthenticationException", attrs.get("kugel.error_type"));
        assertEquals("UNAUTHORIZED", attrs.get("kugel.error_code"));
        assertEquals("ws_req_7", attrs.get("kugel.server_request_id"));
    }

    // ── WebSocket handshake rejection ───────────────────────────────────

    /**
     * Ingress rejects a handshake (401, 429, connection cap) with a plain HTTP
     * response carrying {@code x-request-id}. That id must reach the typed
     * error and {@code kugel.server_request_id}, and the failure is a
     * {@code connection_failed} at the handshake stage.
     */
    @Test
    void rejectedHandshakeCarriesTheRequestIdIntoErrorAndEvent() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            Thread responder = new Thread(() -> rejectOneHandshake(server, "hs_req_9"));
            responder.setDaemon(true);
            responder.start();

            RecordingSender sender = new RecordingSender();
            Diagnostics diagnostics = reporter(sender);
            KugelAudioOptions options = KugelAudioOptions.builder("test-key")
                    .apiUrl("http://localhost:" + server.getLocalPort())
                    .autoConnect(false)
                    .timeout(Duration.ofSeconds(5))
                    .build();
            StreamingSession session = new StreamingSession(
                    options, HttpClient.newHttpClient(), StreamConfig.builder().build(),
                    new StreamCallbacks() { @Override public void onChunk(AudioChunk chunk) {} },
                    diagnostics);

            AuthenticationException thrown =
                    assertThrows(AuthenticationException.class, session::connect);
            assertEquals("hs_req_9", thrown.getRequestId());

            diagnostics.flushNow();
            Map<String, String> attrs = RecordingSender.attributesOf(sender.records().get(0));
            assertEquals("connection_failed", attrs.get("kugel.event"));
            assertEquals("handshake", attrs.get("kugel.failure_stage"));
            assertEquals("stream_session", attrs.get("kugel.operation"));
            assertEquals("401", attrs.get("kugel.http_status"));
            assertEquals("hs_req_9", attrs.get("kugel.server_request_id"));
        }
    }

    @Test
    void handshake403KeepsTheRequestId() {
        java.net.http.HttpResponse<Object> response = handshakeResponse(403, "hs_req_403");
        KugelAudioException typed = Errors.classifyWsHandshake(
                new java.util.concurrent.ExecutionException(
                        new java.net.http.WebSocketHandshakeException(response)));
        assertInstanceOf(AuthenticationException.class, typed);
        assertEquals("hs_req_403", typed.getRequestId());
    }

    /** Answers one upgrade request with {@code 401} and an {@code x-request-id}. */
    private static void rejectOneHandshake(ServerSocket server, String requestId) {
        try (Socket socket = server.accept()) {
            InputStream in = socket.getInputStream();
            int matched = 0;
            byte[] end = "\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
            while (matched < end.length) {
                int b = in.read();
                if (b < 0) return;
                matched = b == end[matched] ? matched + 1 : (b == end[0] ? 1 : 0);
            }
            OutputStream out = socket.getOutputStream();
            out.write(("HTTP/1.1 401 Unauthorized\r\n"
                    + "x-request-id: " + requestId + "\r\n"
                    + "content-length: 0\r\n"
                    + "connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();
        } catch (IOException ignored) {
            // The test fails on its own assertions if the rejection never lands.
        }
    }

    private static java.net.http.HttpResponse<Object> handshakeResponse(int status, String requestId) {
        java.net.http.HttpHeaders headers = java.net.http.HttpHeaders.of(
                Map.of("X-Request-ID", List.of(requestId)), (k, v) -> true);
        return new java.net.http.HttpResponse<>() {
            @Override public int statusCode() { return status; }
            @Override public java.net.http.HttpRequest request() { return null; }
            @Override public java.util.Optional<java.net.http.HttpResponse<Object>> previousResponse() {
                return java.util.Optional.empty();
            }
            @Override public java.net.http.HttpHeaders headers() { return headers; }
            @Override public Object body() { return null; }
            @Override public java.util.Optional<javax.net.ssl.SSLSession> sslSession() {
                return java.util.Optional.empty();
            }
            @Override public java.net.URI uri() { return java.net.URI.create("wss://example/"); }
            @Override public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
        };
    }

    // ── Diagnostics delivery reuses the SDK's own auth ──────────────────

    /**
     * C1: the diagnostics POST goes to the API host the client already calls,
     * with the very same auth headers {@link HttpHelper} puts on a {@code /v1}
     * request. Proven against a real request the SDK built, not a restatement.
     */
    @Test
    void diagnosticsPostReusesTheAuthHeadersOfARealApiCall() {
        KugelAudioOptions options = KugelAudioOptions.builder("test-key")
                .apiUrl("https://tenant.example/api")
                .telemetry(true)
                .build();

        FakeHttpClient client = FakeHttpClient.responding(200, "\"ok\"", Map.of());
        RecordingSender sender = new RecordingSender();
        Diagnostics diagnostics = Diagnostics.withSender(
                DiagnosticsConfig.resolve(options.getEffectiveApiUrl(), options.getTelemetry(), key -> null),
                "2.4.0",
                sender,
                false,
                HttpHelper.authHeaders(options));
        HttpHelper helper = new HttpHelper(client, options, diagnostics);

        helper.get("models", String.class);
        diagnostics.startOperation(Diagnostics.OP_GENERATE, Diagnostics.TRANSPORT_HTTP)
                .stage(Diagnostics.STAGE_CONNECTING)
                .fail(new ConnectionException("boom"));
        diagnostics.flushNow();

        var apiHeaders = client.lastRequest.headers();
        Map<String, String> telemetryHeaders = sender.calls.get(0).headers();

        assertEquals(
                "https://tenant.example/api/v1/sdk-diagnostics",
                sender.calls.get(0).url(),
                "same host as the API call, no new hostname");
        assertEquals(
                apiHeaders.firstValue("Authorization").orElse(null),
                telemetryHeaders.get("Authorization"));
        assertEquals(
                apiHeaders.firstValue("X-API-Key").orElse(null),
                telemetryHeaders.get("X-API-Key"));
        assertEquals("application/json", telemetryHeaders.get("Content-Type"));
    }

    /** The 2-arg constructor shipped publicly in 3.0.0; it must keep working until the next major. */
    @Test
    @SuppressWarnings("deprecation")
    void deprecatedTwoArgConstructorStillWorks() {
        FakeHttpClient client = FakeHttpClient.responding(200, "\"ok\"", Map.of());
        HttpHelper helper = new HttpHelper(client, KugelAudioOptions.builder("test-key").build());
        assertEquals("ok", helper.get("models", String.class));
    }

    // ── SDK identity headers ────────────────────────────────────────────

    @Test
    void httpRequestsCarryTheResolvedSdkVersionNotAHardcodedOne() {
        FakeHttpClient client = FakeHttpClient.responding(200, "\"ok\"", Map.of());
        HttpHelper helper = new HttpHelper(
                client, KugelAudioOptions.builder("test-key").build(), Diagnostics.disabled());
        helper.get("models", String.class);

        var headers = client.lastRequest.headers();
        String version = SdkMetadata.sdkVersion();
        assertNotEquals("unknown", version, "the filtered version resource must be on the classpath");
        assertNotEquals("0.1.0", version, "the old hardcoded User-Agent version must be gone");
        assertEquals("kugelaudio-java/" + version, headers.firstValue("User-Agent").orElse(null));
        assertEquals("java", headers.firstValue("X-KugelAudio-SDK").orElse(null));
        assertEquals(version, headers.firstValue("X-KugelAudio-SDK-Version").orElse(null));
    }
}
