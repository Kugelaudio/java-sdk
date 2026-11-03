package com.kugelaudio.sdk.internal;

import com.kugelaudio.sdk.KugelAudioOptions;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Builds WebSocket URLs and the credential each upgrade carries.
 *
 * <p>An API key or a token rides the query string ({@code api_key=} /
 * {@code token=}); a master key is sent only as an {@code Authorization}
 * header ({@link #authHeaders}), so it never appears in a URL.
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
            case MASTER_KEY -> null; // a header, see authHeaders
            case TOKEN -> "token";
            case API_KEY -> "api_key";
        };

        StringBuilder sb = new StringBuilder(wsUrl);
        if (authParam != null) {
            String encoded = URLEncoder.encode(options.getApiKey(), StandardCharsets.UTF_8);
            appendParam(sb, authParam, encoded);
        }

        if (options.getOrgId() != null) {
            appendParam(sb, "org_id", String.valueOf(options.getOrgId()));
        }

        if (initialMessage != null) {
            appendParam(sb, "initial_message", 
                    URLEncoder.encode(initialMessage, StandardCharsets.UTF_8));
        }

        return sb.toString();
    }

    /**
     * Upgrade headers that carry the credential: {@code Authorization: Bearer}
     * for a master key, none for the modes that ride the query string.
     */
    public static Map<String, String> authHeaders(KugelAudioOptions options) {
        if (options.getAuthMode() != KugelAudioOptions.AuthMode.MASTER_KEY) return Map.of();
        return Map.of("Authorization", "Bearer " + options.getApiKey());
    }

    private static void appendParam(StringBuilder sb, String name, String encodedValue) {
        sb.append(sb.indexOf("?") < 0 ? '?' : '&').append(name).append('=').append(encodedValue);
    }
}
