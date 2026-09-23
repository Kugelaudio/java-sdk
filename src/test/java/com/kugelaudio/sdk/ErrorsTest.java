package com.kugelaudio.sdk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kugelaudio.sdk.internal.Errors;
import org.junit.jupiter.api.Test;

import java.net.http.HttpHeaders;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ErrorsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static HttpHeaders headers(Map<String, String> map) {
        java.util.Map<String, List<String>> hm = new java.util.HashMap<>();
        map.forEach((k, v) -> hm.put(k, List.of(v)));
        return HttpHeaders.of(hm, (k, v) -> true);
    }

    private static HttpHeaders emptyHeaders() {
        return HttpHeaders.of(Map.of(), (k, v) -> true);
    }

    @Test
    void classifyHttpUnknownStatusFallsBackToBase() {
        KugelAudioException ex = Errors.classifyHttp(418, "{\"error\":\"teapot\"}", emptyHeaders(), MAPPER);
        assertEquals(KugelAudioException.class, ex.getClass());
        assertEquals(418, ex.getStatusCode());
    }

    @Test
    void classifyHttp404WithoutErrorCodeStillBuildsNotFound() {
        KugelAudioException ex = Errors.classifyHttp(
                404, "{\"detail\":\"nope\"}", emptyHeaders(), MAPPER);
        assertInstanceOf(NotFoundException.class, ex);
        assertTrue(ex.getMessage().contains("nope"));
        assertEquals("NOT_FOUND", ex.getErrorCode());
    }

    @Test
    void classifyHttpErrorCodeWinsOverStatusMismatch() {
        String body = "{\"error\":\"x\",\"error_code\":\"UNAUTHORIZED\"}";
        KugelAudioException ex = Errors.classifyHttp(500, body, emptyHeaders(), MAPPER);
        assertInstanceOf(AuthenticationException.class, ex);
    }

    @Test
    void classifyHttpFastapiDetailIsRead() {
        KugelAudioException ex = Errors.classifyHttp(
                400, "{\"detail\":\"field required\"}", emptyHeaders(), MAPPER);
        assertInstanceOf(ValidationException.class, ex);
        assertTrue(ex.getMessage().contains("field required"));
    }

    @Test
    void classifyHttpRetryAfterHeaderFallback() {
        KugelAudioException ex = Errors.classifyHttp(
                429, "{\"error\":\"slow\"}",
                headers(Map.of("Retry-After", "12")), MAPPER);
        assertInstanceOf(RateLimitException.class, ex);
        assertEquals(Integer.valueOf(12), ex.getRetryAfter());
    }

    @Test
    void classifyHttpRequestIdHeaderCaptured() {
        KugelAudioException ex = Errors.classifyHttp(
                401, "{\"error\":\"bad\",\"error_code\":\"UNAUTHORIZED\"}",
                headers(Map.of("x-request-id", "req_abc")), MAPPER);
        assertEquals("req_abc", ex.getRequestId());
        assertTrue(ex.getMessage().contains("req_abc"));
    }

    @Test
    void classifyHttpUnparseableBodyFallsBackToText() {
        KugelAudioException ex = Errors.classifyHttp(500, "upstream exploded", emptyHeaders(), MAPPER);
        assertEquals(KugelAudioException.class, ex.getClass());
        assertTrue(ex.getMessage().contains("upstream exploded"));
    }

    @Test
    void classifyWsFrameWithoutErrorCodeIsGeneric() throws Exception {
        JsonNode data = MAPPER.readTree("{\"error\":\"something broke\"}");
        KugelAudioException ex = Errors.classifyWsFrame(data);
        assertEquals(KugelAudioException.class, ex.getClass());
        assertTrue(ex.getMessage().contains("something broke"));
    }

    @Test
    void classifyWsFrameWithRetryAfter() throws Exception {
        JsonNode data = MAPPER.readTree(
                "{\"error\":\"slow\",\"error_code\":\"RATE_LIMITED\",\"retry_after\":3}");
        KugelAudioException ex = Errors.classifyWsFrame(data);
        assertInstanceOf(RateLimitException.class, ex);
        assertEquals(Integer.valueOf(3), ex.getRetryAfter());
    }

    @Test
    void classifyWsFrameMissingVoiceCodeBuildsValidation() throws Exception {
        JsonNode data = MAPPER.readTree(
                "{\"error\":\"voice_id is required\",\"error_code\":\"MISSING_VOICE_ID\",\"code\":400}");
        KugelAudioException ex = Errors.classifyWsFrame(data);
        assertInstanceOf(ValidationException.class, ex);
        assertEquals(400, ex.getStatusCode());
        assertEquals("MISSING_VOICE_ID", ex.getErrorCode());
    }

    @Test
    void classifyWsFrameTooManyContextsCodeBuildsRateLimit() throws Exception {
        JsonNode data = MAPPER.readTree(
                "{\"error\":\"Too many concurrent contexts\",\"error_code\":\"TOO_MANY_CONTEXTS\",\"code\":429}");
        KugelAudioException ex = Errors.classifyWsFrame(data);
        assertInstanceOf(RateLimitException.class, ex);
        assertEquals(429, ex.getStatusCode());
        assertEquals("TOO_MANY_CONTEXTS", ex.getErrorCode());
    }

    @Test
    void classifyWsCloseUnknownCodeIsConnection() {
        KugelAudioException ex = Errors.classifyWsClose(1011, "server error");
        assertInstanceOf(ConnectionException.class, ex);
        assertTrue(ex.getMessage().contains("server error"));
    }

    @Test
    void retryAfterPreservedOn401() {
        KugelAudioException ex = Errors.classifyHttp(
                401, "{\"error\":\"bad\",\"error_code\":\"UNAUTHORIZED\"}",
                headers(Map.of("Retry-After", "30")), MAPPER);
        assertInstanceOf(AuthenticationException.class, ex);
        assertEquals(Integer.valueOf(30), ex.getRetryAfter());
    }

    @Test
    void isErrorCloseCodeMatchesServerErrorCodes() {
        assertTrue(Errors.isErrorCloseCode(4001));
        assertTrue(Errors.isErrorCloseCode(4003));
        assertTrue(Errors.isErrorCloseCode(4029));
        assertTrue(Errors.isErrorCloseCode(4500));
    }

    @Test
    void isErrorCloseCodeRejectsNormalAndSpecCodes() {
        // Matches Python/JS: only 4001/4003/4029/4500 are errors.
        // 1000 (normal), 1001 (going away), 1006 (abnormal), 1011 (server error)
        // are all end-of-stream, not errors to surface.
        assertFalse(Errors.isErrorCloseCode(1000));
        assertFalse(Errors.isErrorCloseCode(1001));
        assertFalse(Errors.isErrorCloseCode(1006));
        assertFalse(Errors.isErrorCloseCode(1011));
        assertFalse(Errors.isErrorCloseCode(0));
    }

    @Test
    void classifyWsHandshake403MapsToAuthenticationException() throws Exception {
        // Server rejects WS upgrades with a bare API key using HTTP 403.
        // Build a real WebSocketHandshakeException via reflection-free means:
        // we fabricate a minimal HttpResponse and wrap in an ExecutionException
        // to mirror what the HttpClient throws from buildAsync().get().
        java.net.http.WebSocketHandshakeException wsh =
                new java.net.http.WebSocketHandshakeException(fakeResponse(403));
        java.util.concurrent.ExecutionException ee =
                new java.util.concurrent.ExecutionException("upgrade failed", wsh);
        KugelAudioException typed = Errors.classifyWsHandshake(ee);
        assertInstanceOf(AuthenticationException.class, typed);
    }

    @Test
    void classifyWsHandshake401MapsToAuthenticationException() throws Exception {
        java.net.http.WebSocketHandshakeException wsh =
                new java.net.http.WebSocketHandshakeException(fakeResponse(401));
        KugelAudioException typed = Errors.classifyWsHandshake(wsh);
        assertInstanceOf(AuthenticationException.class, typed);
    }

    @Test
    void classifyWsHandshake429MapsToRateLimit() throws Exception {
        java.net.http.WebSocketHandshakeException wsh =
                new java.net.http.WebSocketHandshakeException(fakeResponse(429));
        KugelAudioException typed = Errors.classifyWsHandshake(wsh);
        assertInstanceOf(RateLimitException.class, typed);
    }

    @Test
    void classifyWsHandshakeReturnsNullForNonHandshakeException() {
        assertNull(Errors.classifyWsHandshake(new RuntimeException("network down")));
        assertNull(Errors.classifyWsHandshake(null));
    }

    /** Minimal HttpResponse stub carrying just a status code. */
    private static java.net.http.HttpResponse<?> fakeResponse(int status) {
        return new java.net.http.HttpResponse<Object>() {
            @Override public int statusCode() { return status; }
            @Override public java.net.http.HttpRequest request() { return null; }
            @Override public java.util.Optional<java.net.http.HttpResponse<Object>> previousResponse() { return java.util.Optional.empty(); }
            @Override public java.net.http.HttpHeaders headers() { return HttpHeaders.of(Map.of(), (k, v) -> true); }
            @Override public Object body() { return null; }
            @Override public java.util.Optional<javax.net.ssl.SSLSession> sslSession() { return java.util.Optional.empty(); }
            @Override public java.net.URI uri() { return java.net.URI.create("wss://example/"); }
            @Override public java.net.http.HttpClient.Version version() { return java.net.http.HttpClient.Version.HTTP_1_1; }
        };
    }

    @Test
    void fromEnvMessageIsActionableWhenMissing() {
        String before = System.getenv("KUGELAUDIO_API_KEY");
        // We can't unset the env var from Java; skip if it's present.
        if (before != null && !before.isBlank()) return;
        ValidationException ex = assertThrows(ValidationException.class, KugelAudio::fromEnv);
        assertTrue(ex.getMessage().contains("KUGELAUDIO_API_KEY"));
        assertTrue(ex.getMessage().contains("https://app.kugelaudio.com/settings/api-keys"));
    }
}
