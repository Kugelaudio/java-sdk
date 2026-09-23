package com.kugelaudio.sdk.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Encodes {@link DiagnosticsEvent}s as an OTLP/HTTP JSON {@code logs} payload.
 *
 * <p>This class is the privacy boundary. Only keys in {@link #ALLOWED} reach the
 * wire; anything else is dropped silently, whatever the caller put in the event.
 * Never add a free-form key here — no text, no URLs, no hostnames, no exception
 * messages. The allowlist mirrors {@code services/ingress/docs/sdk-diagnostics-contract.md}.
 */
public final class OtlpEncoder {

    public static final String SERVICE_NAME = "kugelaudio-sdk";
    public static final String TELEMETRY_LANGUAGE = "java";
    public static final String SCOPE_NAME = "kugelaudio.diagnostics";
    public static final String SCOPE_VERSION = "1";

    /** OTLP severity for failure events. */
    private static final int SEVERITY_ERROR = 17;
    /** OTLP severity for the {@code sdk_stats} roll-up, which is not a fault. */
    private static final int SEVERITY_INFO = 9;

    /** Every string attribute value must match this, or it is dropped. */
    private static final Pattern STRING_VALUE = Pattern.compile("[A-Za-z0-9_.:/+-]{1,64}");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Attribute value kinds. OTLP JSON encodes int64 as a decimal string. */
    private enum Kind { STRING, INT }

    private static final Map<String, Kind> ALLOWED = Map.ofEntries(
            Map.entry("kugel.event", Kind.STRING),
            Map.entry("kugel.event_id", Kind.STRING),
            Map.entry("kugel.operation_id", Kind.STRING),
            Map.entry("kugel.operation", Kind.STRING),
            Map.entry("kugel.sdk.name", Kind.STRING),
            Map.entry("kugel.sdk.version", Kind.STRING),
            Map.entry("kugel.runtime", Kind.STRING),
            Map.entry("kugel.integration", Kind.STRING),
            Map.entry("kugel.transport", Kind.STRING),
            Map.entry("kugel.failure_stage", Kind.STRING),
            Map.entry("kugel.error_type", Kind.STRING),
            Map.entry("kugel.error_code", Kind.STRING),
            Map.entry("kugel.http_status", Kind.INT),
            Map.entry("kugel.ws_close_code", Kind.INT),
            Map.entry("kugel.server_request_id", Kind.STRING),
            Map.entry("kugel.elapsed_ms", Kind.INT),
            Map.entry("kugel.audio_chunks", Kind.INT),
            Map.entry("kugel.audio_bytes", Kind.INT),
            Map.entry("kugel.retry_count", Kind.INT),
            Map.entry("kugel.outcome", Kind.STRING),
            Map.entry("kugel.endpoint_kind", Kind.STRING),
            Map.entry("kugel.success_count", Kind.INT),
            Map.entry("kugel.failure_count", Kind.INT),
            Map.entry("kugel.cancelled_count", Kind.INT));

    private OtlpEncoder() {}

    /** True if {@code key} may be sent. Exposed so tests can assert the allowlist. */
    public static boolean isAllowed(String key) {
        return ALLOWED.containsKey(key);
    }

    /** The full record-attribute allowlist. */
    public static Set<String> allowedKeys() {
        return ALLOWED.keySet();
    }

    /**
     * Builds the OTLP/HTTP JSON body for one batch.
     *
     * @param sdkVersion value of the {@code service.version} resource attribute
     * @param events     the batch (never empty in practice)
     */
    public static byte[] encode(String sdkVersion, List<DiagnosticsEvent> events) {
        ObjectNode root = MAPPER.createObjectNode();
        ArrayNode resourceLogs = root.putArray("resourceLogs");
        ObjectNode resourceLog = resourceLogs.addObject();

        ArrayNode resourceAttrs = resourceLog.putObject("resource").putArray("attributes");
        putString(resourceAttrs, "service.name", SERVICE_NAME);
        putString(resourceAttrs, "service.version", sdkVersion);
        putString(resourceAttrs, "telemetry.sdk.language", TELEMETRY_LANGUAGE);

        ObjectNode scopeLog = resourceLog.putArray("scopeLogs").addObject();
        ObjectNode scope = scopeLog.putObject("scope");
        scope.put("name", SCOPE_NAME);
        scope.put("version", SCOPE_VERSION);

        ArrayNode records = scopeLog.putArray("logRecords");
        for (DiagnosticsEvent event : events) {
            records.add(encodeRecord(event));
        }

        try {
            return MAPPER.writeValueAsBytes(root);
        } catch (Exception e) {
            // Jackson cannot fail on a tree it built itself; keep the reporter silent.
            return "{}".getBytes(StandardCharsets.UTF_8);
        }
    }

    private static ObjectNode encodeRecord(DiagnosticsEvent event) {
        ObjectNode record = MAPPER.createObjectNode();
        record.put("timeUnixNano", Long.toString(event.getTimeUnixNano()));
        boolean stats = Diagnostics.EVENT_SDK_STATS.equals(event.getName());
        record.put("severityNumber", stats ? SEVERITY_INFO : SEVERITY_ERROR);
        record.put("severityText", stats ? "INFO" : "ERROR");
        record.putObject("body").put("stringValue", event.getName());

        ArrayNode attrs = record.putArray("attributes");
        for (Map.Entry<String, Object> entry : event.getAttributes().entrySet()) {
            Kind kind = ALLOWED.get(entry.getKey());
            Object value = entry.getValue();
            if (kind == null || value == null) {
                continue;
            }
            if (kind == Kind.INT) {
                if (value instanceof Number number) {
                    putInt(attrs, entry.getKey(), number.longValue());
                }
                continue;
            }
            if (value instanceof Number) {
                // A number where a string is specified is a coding error, not data.
                continue;
            }
            String text = value.toString();
            if (STRING_VALUE.matcher(text).matches()) {
                putString(attrs, entry.getKey(), text);
            }
        }
        return record;
    }

    private static void putString(ArrayNode target, String key, String value) {
        ObjectNode attr = target.addObject();
        attr.put("key", key);
        attr.putObject("value").put("stringValue", value);
    }

    private static void putInt(ArrayNode target, String key, long value) {
        ObjectNode attr = target.addObject();
        attr.put("key", key);
        attr.putObject("value").put("intValue", Long.toString(value));
    }
}
