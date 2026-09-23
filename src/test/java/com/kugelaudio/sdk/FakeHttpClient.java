package com.kugelaudio.sdk;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * An {@link HttpClient} that answers every request from a canned response, or
 * throws a canned {@link IOException}. Lets the SDK's real HTTP path — headers,
 * error classification, diagnostics — be exercised without touching the network.
 */
final class FakeHttpClient extends HttpClient {

    private final int status;
    private final String body;
    private final Map<String, List<String>> headers;
    private final IOException failure;

    /** Records the request the SDK actually built. */
    volatile HttpRequest lastRequest;

    private FakeHttpClient(int status, String body, Map<String, List<String>> headers, IOException failure) {
        this.status = status;
        this.body = body;
        this.headers = headers;
        this.failure = failure;
    }

    static FakeHttpClient responding(int status, String body, Map<String, List<String>> headers) {
        return new FakeHttpClient(status, body, headers, null);
    }

    static FakeHttpClient failing(IOException failure) {
        return new FakeHttpClient(0, "", Map.of(), failure);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler)
            throws IOException {
        this.lastRequest = request;
        if (failure != null) throw failure;
        return (HttpResponse<T>) new FakeResponse(request, status, body, HttpHeaders.of(headers, (k, v) -> true));
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
            HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) {
        try {
            return CompletableFuture.completedFuture(send(request, responseBodyHandler));
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
            HttpRequest request,
            HttpResponse.BodyHandler<T> responseBodyHandler,
            HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
        return sendAsync(request, responseBodyHandler);
    }

    @Override public Optional<CookieHandler> cookieHandler() { return Optional.empty(); }
    @Override public Optional<Duration> connectTimeout() { return Optional.empty(); }
    @Override public Redirect followRedirects() { return Redirect.NEVER; }
    @Override public Optional<ProxySelector> proxy() { return Optional.empty(); }
    @Override public Optional<Authenticator> authenticator() { return Optional.empty(); }
    @Override public Version version() { return Version.HTTP_1_1; }
    @Override public Optional<Executor> executor() { return Optional.empty(); }

    @Override
    public SSLContext sslContext() {
        try {
            return SSLContext.getDefault();
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public SSLParameters sslParameters() {
        return new SSLParameters();
    }

    private record FakeResponse(HttpRequest req, int code, String payload, HttpHeaders hdrs)
            implements HttpResponse<String> {

        @Override public int statusCode() { return code; }
        @Override public HttpRequest request() { return req; }
        @Override public Optional<HttpResponse<String>> previousResponse() { return Optional.empty(); }
        @Override public HttpHeaders headers() { return hdrs; }
        @Override public String body() { return payload; }
        @Override public Optional<SSLSession> sslSession() { return Optional.empty(); }
        @Override public URI uri() { return req.uri(); }
        @Override public Version version() { return Version.HTTP_1_1; }
    }
}
