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
import java.util.List;

/**
 * Internal HTTP helper for REST API calls. Handles auth headers and error mapping.
 */
public final class HttpHelper {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final HttpClient httpClient;
    private final KugelAudioOptions options;

    public HttpHelper(HttpClient httpClient, KugelAudioOptions options) {
        this.httpClient = httpClient;
        this.options = options;
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

    private HttpRequest.Builder buildRequest(String path) {
        String baseUrl = options.getEffectiveApiUrl();
        String url = baseUrl.endsWith("/") ? baseUrl + path : baseUrl + "/" + path;
        return HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(options.getTimeout())
                .header("Authorization", "Bearer " + options.getApiKey())
                .header("X-API-Key", options.getApiKey())
                .header("User-Agent", "kugelaudio-java/0.1.0");
    }

    private HttpResponse<String> execute(HttpRequest request) {
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            checkErrors(response);
            return response;
        } catch (KugelAudioException e) {
            throw e;
        } catch (IOException e) {
            throw new ConnectionException("Connection failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ConnectionException("Request interrupted", e);
        }
    }

    private void checkErrors(HttpResponse<String> response) {
        int status = response.statusCode();
        if (status >= 200 && status < 300) return;

        String message = "HTTP " + status;
        try {
            JsonNode json = MAPPER.readTree(response.body());
            if (json.has("detail")) message = json.get("detail").asText();
            else if (json.has("error")) message = json.get("error").asText();
        } catch (Exception ignored) {
            if (response.body() != null && !response.body().isBlank()) {
                message = response.body();
            }
        }

        switch (status) {
            case 401 -> throw new AuthenticationException(message);
            case 403 -> throw new InsufficientCreditsException(message);
            case 400 -> throw new ValidationException(message);
            case 429 -> throw new RateLimitException(message);
            default -> throw new KugelAudioException(message, status);
        }
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
