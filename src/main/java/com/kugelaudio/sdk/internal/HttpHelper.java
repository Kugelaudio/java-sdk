package com.kugelaudio.sdk.internal;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kugelaudio.sdk.*;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Internal HTTP helper for REST API calls. Handles auth headers and error mapping.
 */
public final class HttpHelper {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final HttpClient httpClient;
    private final KugelAudioOptions options;
    private final Diagnostics diagnostics;

    /**
     * Internal API, kept only because this constructor shipped publicly in
     * java-sdk 3.0.0. Reports no diagnostics.
     *
     * @deprecated internal; will be removed in the next major version. Use
     *     {@link com.kugelaudio.sdk.KugelAudio} instead of constructing this class.
     */
    @Deprecated
    public HttpHelper(HttpClient httpClient, KugelAudioOptions options) {
        this(httpClient, options, Diagnostics.disabled());
    }

    public HttpHelper(HttpClient httpClient, KugelAudioOptions options, Diagnostics diagnostics) {
        this.httpClient = httpClient;
        this.options = options;
        this.diagnostics = java.util.Objects.requireNonNull(diagnostics, "diagnostics");
    }

    public <T> T get(String path, Class<T> type) {
        HttpResponse<String> response = execute(buildRequest(path).GET().build());
        return deserialize(response.body(), type);
    }

    public <T> List<T> getList(String path, TypeReference<List<T>> typeRef) {
        HttpResponse<String> response = execute(buildRequest(path).GET().build());
        return deserializeList(response.body(), typeRef);
    }

    /**
     * GET a JSON envelope like {"key": [...]} and return the unwrapped list.
     */
    public <T> List<T> getListFromEnvelope(String path, String key, Class<T> elementType) {
        HttpResponse<String> response = execute(buildRequest(path).GET().build());
        try {
            JsonNode root = MAPPER.readTree(response.body());
            JsonNode arrayNode = root.get(key);
            if (arrayNode == null || !arrayNode.isArray()) {
                throw new KugelAudioException(
                        "Expected JSON envelope with key '" + key + "', got: " + root.getNodeType());
            }
            return MAPPER.readerForListOf(elementType).readValue(arrayNode);
        } catch (KugelAudioException e) {
            throw e;
        } catch (IOException e) {
            throw new KugelAudioException("Failed to parse response: " + e.getMessage(), e);
        }
    }

    public <T> T post(String path, Object body, Class<T> type) {
        HttpRequest.Builder builder = buildRequest(path)
                .header("Content-Type", "application/json");
        if (body != null) {
            builder.POST(HttpRequest.BodyPublishers.ofString(serialize(body)));
        } else {
            builder.POST(HttpRequest.BodyPublishers.noBody());
        }
        HttpResponse<String> response = execute(builder.build());
        if (type == Void.class) return null;
        return deserialize(response.body(), type);
    }

    public <T> T patch(String path, Object body, Class<T> type) {
        HttpRequest.Builder builder = buildRequest(path)
                .header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(serialize(body)));
        HttpResponse<String> response = execute(builder.build());
        return deserialize(response.body(), type);
    }

    public <T> T put(String path, Object body, Class<T> type) {
        HttpRequest.Builder builder = buildRequest(path)
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(serialize(body)));
        HttpResponse<String> response = execute(builder.build());
        return deserialize(response.body(), type);
    }

    public void delete(String path) {
        execute(buildRequest(path).DELETE().build());
    }

    /**
     * Sends a multipart form-data request. Used for voice creation with file uploads.
     */
    public <T> T postMultipart(String path, String boundary, byte[] multipartBody, Class<T> type) {
        HttpRequest request = buildRequest(path)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(multipartBody))
                .build();
        HttpResponse<String> response = execute(request);
        return deserialize(response.body(), type);
    }

    /**
     * The auth headers every KugelAudio API call carries. Single source of
     * truth: the diagnostics reporter reuses this rather than re-deriving a
     * second auth scheme for {@code /v1/sdk-diagnostics}.
     */
    public static Map<String, String> authHeaders(KugelAudioOptions options) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer " + options.getApiKey());
        headers.put("X-API-Key", options.getApiKey());
        return Collections.unmodifiableMap(headers);
    }

    private HttpRequest.Builder buildRequest(String path) {
        String baseUrl = options.getEffectiveApiUrl();
        String url = baseUrl.endsWith("/") ? baseUrl + path : baseUrl + "/" + path;
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(options.getTimeout());
        authHeaders(options).forEach(builder::header);
        SdkMetadata.sdkHeaders().forEach(builder::header);
        return builder;
    }

    private HttpResponse<String> execute(HttpRequest request) {
        Diagnostics.Operation op = diagnostics.startOperation(
                operationFor(request.uri()), Diagnostics.TRANSPORT_HTTP);
        op.stage(Diagnostics.STAGE_CONNECTING);
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            // A response arrived: the failure, if any, is server-side.
            op.stage(Diagnostics.STAGE_SENDING_REQUEST)
                    .httpStatus(response.statusCode())
                    .serverRequestId(Errors.requestIdOf(response.headers()));
            checkErrors(response);
            op.success();
            return response;
        } catch (KugelAudioException e) {
            // Only checkErrors() throws this: the server answered with an error.
            op.rejected(e);
            throw e;
        } catch (java.net.http.HttpTimeoutException e) {
            ConnectionException wrapped = new ConnectionException(
                    "Request to " + request.method() + " " + request.uri()
                            + " timed out after " + options.getTimeout().toMillis() + "ms.",
                    e);
            op.fail(wrapped);
            throw wrapped;
        } catch (IOException e) {
            ConnectionException wrapped = new ConnectionException(
                    "Could not reach KugelAudio at " + request.uri() + ": "
                            + e.getMessage() + ". Check network connectivity.",
                    e);
            op.fail(wrapped);
            throw wrapped;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            ConnectionException wrapped = new ConnectionException(
                    "Request to " + request.method() + " " + request.uri()
                            + " was interrupted.",
                    e);
            op.fail(wrapped);
            throw wrapped;
        }
    }

    /**
     * Maps a REST path to a {@code kugel.operation} value. Returns null for
     * paths outside the enum rather than inventing a value.
     */
    static String operationFor(URI uri) {
        String path = uri == null || uri.getPath() == null
                ? "" : uri.getPath().toLowerCase(java.util.Locale.ROOT);
        if (path.contains("/voices")) return Diagnostics.OP_VOICES;
        if (path.contains("/dictionaries")) return Diagnostics.OP_DICTIONARIES;
        if (path.contains("/models")) return Diagnostics.OP_MODELS;
        if (path.contains("/transcri") || path.contains("/asr")) return Diagnostics.OP_TRANSCRIBE;
        return null;
    }

    private void checkErrors(HttpResponse<String> response) {
        int status = response.statusCode();
        if (status >= 200 && status < 300) return;
        throw Errors.classifyHttp(status, response.body(), response.headers(), MAPPER);
    }

    private String serialize(Object obj) {
        try {
            return MAPPER.writeValueAsString(obj);
        } catch (IOException e) {
            throw new KugelAudioException("Failed to serialize request body", e);
        }
    }

    private <T> T deserialize(String json, Class<T> type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (IOException e) {
            throw new KugelAudioException("Failed to parse response: " + e.getMessage(), e);
        }
    }

    private <T> List<T> deserializeList(String json, TypeReference<List<T>> typeRef) {
        try {
            return MAPPER.readValue(json, typeRef);
        } catch (IOException e) {
            throw new KugelAudioException("Failed to parse response: " + e.getMessage(), e);
        }
    }
}
