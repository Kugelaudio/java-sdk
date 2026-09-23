package com.kugelaudio.sdk.internal;

import java.io.InputStream;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * SDK identity sent to ingress for observability.
 *
 * <p>Mirrors {@code kugelaudio/_sdk_metadata.py} in the Python SDK so all three
 * SDKs identify themselves with the same header set.
 *
 * <p>The version is resolved at runtime, never hardcoded:
 * <ol>
 *   <li>{@code /com/kugelaudio/sdk/version.properties}, filtered by Maven from
 *       {@code ${project.version}} at build time (works from a classes directory,
 *       so tests see the real version too);</li>
 *   <li>{@code Package.getImplementationVersion()} from the jar manifest;</li>
 *   <li>{@code "unknown"}.</li>
 * </ol>
 */
public final class SdkMetadata {

    /** Value of the {@code kugel.sdk.name} attribute and the {@code X-KugelAudio-SDK} header. */
    public static final String SDK_NAME = "java";

    public static final String HEADER_SDK = "X-KugelAudio-SDK";
    public static final String HEADER_SDK_VERSION = "X-KugelAudio-SDK-Version";

    private static final String VERSION_RESOURCE = "/com/kugelaudio/sdk/version.properties";
    private static final String UNKNOWN = "unknown";

    private static final String VERSION = resolveVersion();
    private static final Map<String, String> HEADERS = buildHeaders(VERSION);

    private SdkMetadata() {}

    /** The installed SDK version, e.g. {@code 2.4.0}, or {@code "unknown"}. */
    public static String sdkVersion() {
        return VERSION;
    }

    /** {@code kugelaudio-java/<version>}. */
    public static String userAgent() {
        return "kugelaudio-java/" + VERSION;
    }

    /**
     * Identity headers to attach to every HTTP request and WebSocket handshake.
     * Immutable; safe to iterate concurrently.
     */
    public static Map<String, String> sdkHeaders() {
        return HEADERS;
    }

    private static Map<String, String> buildHeaders(String version) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put(HEADER_SDK, SDK_NAME);
        headers.put(HEADER_SDK_VERSION, version);
        headers.put("User-Agent", "kugelaudio-java/" + version);
        return Collections.unmodifiableMap(headers);
    }

    private static String resolveVersion() {
        try (InputStream in = SdkMetadata.class.getResourceAsStream(VERSION_RESOURCE)) {
            if (in != null) {
                Properties props = new Properties();
                props.load(in);
                String value = props.getProperty("version");
                if (value != null) {
                    value = value.trim();
                    // Unfiltered placeholder (resource copied without Maven filtering).
                    if (!value.isEmpty() && !value.startsWith("${")) {
                        return value;
                    }
                }
            }
        } catch (Exception ignored) {
            // Fall through to the manifest.
        }
        try {
            String implementation = SdkMetadata.class.getPackage().getImplementationVersion();
            if (implementation != null && !implementation.isBlank()) {
                return implementation.trim();
            }
        } catch (Exception ignored) {
            // Fall through to the constant.
        }
        return UNKNOWN;
    }
}
