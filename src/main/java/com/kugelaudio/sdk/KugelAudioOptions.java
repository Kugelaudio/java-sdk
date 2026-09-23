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
    private final Boolean telemetry;

    private static final String DEFAULT_API_URL = "https://api.kugelaudio.com";
    private static final String[] REGION_PREFIXES = {"eu-", "us-", "global-"};

    private KugelAudioOptions(Builder builder) {
        Objects.requireNonNull(builder.apiKey, "apiKey is required");

        // Strip region prefix from API key
        String key = builder.apiKey;
        Region detectedRegion = null;
        for (String prefix : REGION_PREFIXES) {
            if (key.startsWith(prefix)) {
                detectedRegion = Region.valueOf(prefix.substring(0, prefix.length() - 1).toUpperCase());
                key = key.substring(prefix.length());
                break;
            }
        }
        this.apiKey = key;

        this.authMode = builder.authMode;
        this.orgId = builder.orgId;

        // Resolve API URL: explicit apiUrl > explicit region > key prefix > default.
        if (builder.apiUrlSet) {
            this.apiUrl = builder.apiUrl;
        } else {
            Region effectiveRegion = builder.region != null ? builder.region : detectedRegion;
            this.apiUrl = effectiveRegion == Region.EU ? Region.EU.getUrl() : DEFAULT_API_URL;
        }

        this.ttsUrl = builder.ttsUrl;
        this.timeout = builder.timeout;
        this.autoConnect = builder.autoConnect;
        this.keepalivePingInterval = builder.keepalivePingInterval;
        this.telemetry = builder.telemetry;
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
     * The explicit anonymous-diagnostics setting, or {@code null} when the
     * caller did not set one (the default then depends on whether the API URL
     * is a hosted KugelAudio endpoint). The environment variable
     * {@code KUGELAUDIO_TELEMETRY} overrides this either way.
     */
    public Boolean getTelemetry() { return telemetry; }

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
        private Region region;
        private String apiUrl = DEFAULT_API_URL;
        private boolean apiUrlSet = false;
        private String ttsUrl;
        private Duration timeout = Duration.ofSeconds(60);
        private boolean autoConnect = true;
        private Duration keepalivePingInterval = Duration.ofSeconds(20);
        private Boolean telemetry;

        private Builder(String apiKey) {
            this.apiKey = apiKey;
        }

        /** Use master key authentication (bypasses billing). */
        public Builder masterKey() { this.authMode = AuthMode.MASTER_KEY; return this; }

        /** Use JWT token authentication (requires orgId for billing). */
        public Builder token() { this.authMode = AuthMode.TOKEN; return this; }

        /** Organization ID for token-based auth. */
        public Builder orgId(int orgId) { this.orgId = orgId; return this; }

        /** Deployment region. Takes precedence over API-key prefix but not over {@link #apiUrl}. */
        public Builder region(Region region) { this.region = region; return this; }

        /** Base URL for REST API (default: https://api.kugelaudio.com). Takes precedence over {@link #region}. */
        public Builder apiUrl(String apiUrl) { this.apiUrl = apiUrl; this.apiUrlSet = true; return this; }

        /** Separate URL for TTS WebSocket (defaults to apiUrl). */
        public Builder ttsUrl(String ttsUrl) { this.ttsUrl = ttsUrl; return this; }

        /** HTTP request and WebSocket acquisition timeout (default: 60s). */
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

        /**
         * Enables or disables anonymous client-error diagnostics.
         *
         * <p>Diagnostics report only the shape of a failure — event, stage,
         * error class, elapsed time, chunk counts and the server's request id.
         * Never your text, audio, API key, URLs or exception messages.
         *
         * <p>Unset by default, which means: on for hosted
         * {@code *.kugelaudio.com} endpoints, off for custom / on-premise base
         * URLs. The {@code KUGELAUDIO_TELEMETRY} environment variable
         * overrides this setting in both directions.
         */
        public Builder telemetry(boolean enabled) { this.telemetry = enabled; return this; }

        public KugelAudioOptions build() {
            if (authMode == AuthMode.TOKEN && orgId == null) {
                throw new IllegalArgumentException("orgId is required for token auth");
            }
            return new KugelAudioOptions(this);
        }
    }
}
