package com.kugelaudio.sdk.internal;

import com.kugelaudio.sdk.KugelAudioOptions;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Builds WebSocket URLs with proper auth query parameters.
 */
public final class WsUrlBuilder {

    private WsUrlBuilder() {}

    /**
     * Builds the WebSocket URL for the given endpoint path.
     *
     * @param options Client options containing auth and URL config
     * @param wsPath  WebSocket path, e.g. "/ws/tts" or "/ws/tts/stream"
     * @return Full WebSocket URL with auth parameters
     */
    public static String build(KugelAudioOptions options, String wsPath) {
        return build(options, wsPath, null);
    }

    /**
     * Builds the WebSocket URL with an optional initial message embedded as a query parameter.
     * When {@code initialMessage} is non-null, the server processes it immediately after
     * accepting the connection, eliminating one full RTT from the TTFA path.
     *
     * @param options        Client options containing auth and URL config
     * @param wsPath         WebSocket path, e.g. "/ws/tts" or "/ws/tts/stream"
     * @param initialMessage JSON payload to embed in the URL (null to omit)
     * @return Full WebSocket URL with auth and optional initial_message parameters
     */
    public static String build(KugelAudioOptions options, String wsPath, String initialMessage) {
        String baseUrl = options.getEffectiveTtsUrl();
        String wsUrl = baseUrl
                .replace("https://", "wss://")
                .replace("http://", "ws://");
        wsUrl = wsUrl.replaceAll("/+$", "") + wsPath;

        String authParam = switch (options.getAuthMode()) {
            case MASTER_KEY -> "master_key";
            case TOKEN -> "token";
            case API_KEY -> "api_key";
        };

        String encoded = URLEncoder.encode(options.getApiKey(), StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder(wsUrl);
        sb.append("?").append(authParam).append("=").append(encoded);

        if (options.getOrgId() != null) {
            sb.append("&org_id=").append(options.getOrgId());
        }

        if (initialMessage != null) {
            sb.append("&initial_message=").append(
                    URLEncoder.encode(initialMessage, StandardCharsets.UTF_8));
        }

        return sb.toString();
    }
}
