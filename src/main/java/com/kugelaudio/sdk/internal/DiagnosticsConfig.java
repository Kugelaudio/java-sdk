package com.kugelaudio.sdk.internal;

import java.net.URI;
import java.util.Locale;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * Resolves whether client-error diagnostics are enabled, and where they go.
 *
 * <p>Resolution order (first match wins), identical in the Python, JS and Java
 * SDKs:
 * <ol>
 *   <li>env {@code KUGELAUDIO_TELEMETRY} — {@code 0/false/off/no} disables,
 *       {@code 1/true/on/yes} enables;</li>
 *   <li>the explicit client option ({@code KugelAudioOptions.Builder#telemetry});</li>
 *   <li>default: enabled only when the effective API host ends in
 *       {@code .kugelaudio.com} (a hosted endpoint). Custom / on-premise base
 *       URLs are off by default.</li>
 * </ol>
 *
 * <p>Diagnostics are delivered to {@code <effective API base URL>/v1/sdk-diagnostics}
 * — the same authenticated host the SDK already calls, with the SDK's own auth
 * headers. There is no public ingestion token: a credential shipped inside a
 * published package could not be scoped and could not prove who sent an event.
 * There is no endpoint override; tests inject a {@link Diagnostics.Sender}
 * instead. Contract: {@code services/ingress/docs/sdk-diagnostics-contract.md}.
 */
public final class DiagnosticsConfig {

    /** Path appended to the effective API base URL. */
    public static final String DIAGNOSTICS_PATH = "/v1/sdk-diagnostics";

    public static final String ENV_TELEMETRY = "KUGELAUDIO_TELEMETRY";

    public static final String ENDPOINT_HOSTED = "hosted";
    public static final String ENDPOINT_CUSTOM = "custom";

    private static final String HOSTED_SUFFIX = ".kugelaudio.com";
    private static final Set<String> TRUTHY = Set.of("1", "true", "on", "yes");
    private static final Set<String> FALSY = Set.of("0", "false", "off", "no");

    private final boolean enabled;
    private final String diagnosticsUrl;
    private final String endpointKind;

    private DiagnosticsConfig(boolean enabled, String diagnosticsUrl, String endpointKind) {
        this.enabled = enabled;
        this.diagnosticsUrl = diagnosticsUrl;
        this.endpointKind = endpointKind;
    }

    /** Resolves against the process environment. */
    public static DiagnosticsConfig resolve(String apiUrl, Boolean explicit) {
        return resolve(apiUrl, explicit, System::getenv);
    }

    /**
     * Resolves against an injectable environment lookup (used by tests).
     *
     * @param apiUrl   the effective API base URL
     * @param explicit the explicit client option, or {@code null} if unset
     * @param env      environment variable lookup
     */
    public static DiagnosticsConfig resolve(String apiUrl, Boolean explicit, UnaryOperator<String> env) {
        String kind = isHosted(apiUrl) ? ENDPOINT_HOSTED : ENDPOINT_CUSTOM;

        Boolean fromEnv = parseBoolean(env.apply(ENV_TELEMETRY));
        boolean enabled;
        if (fromEnv != null) {
            enabled = fromEnv;
        } else if (explicit != null) {
            enabled = explicit;
        } else {
            enabled = ENDPOINT_HOSTED.equals(kind);
        }
        return new DiagnosticsConfig(enabled, diagnosticsUrlFor(apiUrl), kind);
    }

    /** A config that is off in every respect. */
    public static DiagnosticsConfig disabled() {
        return new DiagnosticsConfig(false, "", ENDPOINT_CUSTOM);
    }

    /** Direct construction, for tests. {@code diagnosticsUrl} is the full target URL. */
    public static DiagnosticsConfig of(boolean enabled, String diagnosticsUrl, String endpointKind) {
        return new DiagnosticsConfig(
                enabled,
                diagnosticsUrl == null ? "" : diagnosticsUrl.trim(),
                endpointKind == null ? ENDPOINT_CUSTOM : endpointKind);
    }

    /** Whether diagnostics are enabled (events are built, queued and delivered). */
    public boolean isEnabled() {
        return enabled;
    }

    /** The full URL a batch is POSTed to, {@code <api base>/v1/sdk-diagnostics}. */
    public String getDiagnosticsUrl() {
        return diagnosticsUrl;
    }

    /** {@code hosted} or {@code custom} — the {@code kugel.endpoint_kind} attribute. */
    public String getEndpointKind() {
        return endpointKind;
    }

    /** Derives the diagnostics URL from an API base URL. */
    static String diagnosticsUrlFor(String apiUrl) {
        if (apiUrl == null || apiUrl.isBlank()) return "";
        return apiUrl.trim().replaceAll("/+$", "") + DIAGNOSTICS_PATH;
    }

    /** True when the host of {@code apiUrl} ends in {@code .kugelaudio.com}. */
    public static boolean isHosted(String apiUrl) {
        if (apiUrl == null || apiUrl.isBlank()) return false;
        try {
            String host = URI.create(apiUrl.trim()).getHost();
            if (host == null) return false;
            return host.toLowerCase(Locale.ROOT).endsWith(HOSTED_SUFFIX);
        } catch (Exception e) {
            return false;
        }
    }

    private static Boolean parseBoolean(String raw) {
        if (raw == null) return null;
        String value = raw.trim().toLowerCase(Locale.ROOT);
        if (TRUTHY.contains(value)) return Boolean.TRUE;
        if (FALSY.contains(value)) return Boolean.FALSE;
        return null;
    }
}
