package com.kugelaudio.sdk;

import java.time.Duration;
import java.util.Objects;

/**
 * Configuration options for the KugelAudio client.
 *
 * <pre>{@code
 * var options = KugelAudioOptions.builder("your-api-key")
 *     .apiUrl("https://api.kugelaudio.com")
 *     .timeout(Duration.ofSeconds(60))
 *     .build();
 * }</pre>
 */
public final class KugelAudioOptions {

    public enum AuthMode {
        API_KEY,
        MASTER_KEY,
        TOKEN
    }

    private final String apiKey;
    private final AuthMode authMode;
    private final Integer orgId;
    private final String apiUrl;
    private final String ttsUrl;
    private final Duration timeout;
    private final boolean autoConnect;
    private final Duration keepalivePingInterval;

    private KugelAudioOptions(Builder builder) {
        this.apiKey = Objects.requireNonNull(builder.apiKey, "apiKey is required");
        this.authMode = builder.authMode;
        this.orgId = builder.orgId;
        this.apiUrl = builder.apiUrl;
        this.ttsUrl = builder.ttsUrl;
        this.timeout = builder.timeout;
        this.autoConnect = builder.autoConnect;
        this.keepalivePingInterval = builder.keepalivePingInterval;
    }

    public String getApiKey() { return apiKey; }
    public AuthMode getAuthMode() { return authMode; }
    public Integer getOrgId() { return orgId; }
    public String getApiUrl() { return apiUrl; }
    public String getTtsUrl() { return ttsUrl; }
    public Duration getTimeout() { return timeout; }
    public boolean isAutoConnect() { return autoConnect; }
    /** Interval between WebSocket keepalive pings, or null to disable. */
    public Duration getKeepalivePingInterval() { return keepalivePingInterval; }

    /**
     * Returns the effective base URL for REST API calls.
     */
    public String getEffectiveApiUrl() {
        return apiUrl;
    }

    /**
     * Returns the effective base URL for TTS WebSocket connections.
     * Falls back to apiUrl if ttsUrl is not set.
     */
    public String getEffectiveTtsUrl() {
        return ttsUrl != null ? ttsUrl : apiUrl;
    }

    public static Builder builder(String apiKey) {
        return new Builder(apiKey);
    }

    public static final class Builder {
        private final String apiKey;
        private AuthMode authMode = AuthMode.API_KEY;
        private Integer orgId;
        private String apiUrl = "https://api.kugelaudio.com";
        private String ttsUrl;
        private Duration timeout = Duration.ofSeconds(60);
        private boolean autoConnect = true;
        private Duration keepalivePingInterval = Duration.ofSeconds(20);

        private Builder(String apiKey) {
            this.apiKey = apiKey;
        }

        /** Use master key authentication (bypasses billing). */
        public Builder masterKey() { this.authMode = AuthMode.MASTER_KEY; return this; }

        /** Use JWT token authentication (requires orgId for billing). */
        public Builder token() { this.authMode = AuthMode.TOKEN; return this; }

        /** Organization ID for token-based auth. */
        public Builder orgId(int orgId) { this.orgId = orgId; return this; }

        /** Base URL for REST API (default: https://api.kugelaudio.com). */
        public Builder apiUrl(String apiUrl) { this.apiUrl = apiUrl; return this; }

        /** Separate URL for TTS WebSocket (defaults to apiUrl). */
        public Builder ttsUrl(String ttsUrl) { this.ttsUrl = ttsUrl; return this; }

        /** HTTP request timeout (default: 60s). */
        public Builder timeout(Duration timeout) { this.timeout = timeout; return this; }

        /**
         * Whether to eagerly establish the TTS WebSocket connection in the background
         * when the client is created (default: true). This eliminates ~150-300ms of
         * handshake latency from the first TTS request.
         */
        public Builder autoConnect(boolean autoConnect) { this.autoConnect = autoConnect; return this; }

        /**
         * Interval between WebSocket ping frames sent on the pooled connection to
         * prevent idle timeouts (default: 20s). Set to null to disable keepalive pings.
         */
        public Builder keepalivePingInterval(Duration interval) { this.keepalivePingInterval = interval; return this; }

        public KugelAudioOptions build() {
            if (authMode == AuthMode.TOKEN && orgId == null) {
                throw new IllegalArgumentException("orgId is required for token auth");
            }
            return new KugelAudioOptions(this);
        }
    }
}
