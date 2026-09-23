package com.kugelaudio.sdk.internal;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One diagnostics record: an event name plus its attributes.
 *
 * <p>Attribute values are {@link String} or {@link Number}. The
 * {@link OtlpEncoder} is the only place that decides which of them are allowed
 * on the wire — nothing here is trusted.
 */
public final class DiagnosticsEvent {

    private final String name;
    private final long timeUnixNano;
    private final Map<String, Object> attributes;

    public DiagnosticsEvent(String name, Map<String, Object> attributes) {
        this(name, System.currentTimeMillis() * 1_000_000L, attributes);
    }

    public DiagnosticsEvent(String name, long timeUnixNano, Map<String, Object> attributes) {
        this.name = name;
        this.timeUnixNano = timeUnixNano;
        this.attributes = Collections.unmodifiableMap(
                attributes == null ? new LinkedHashMap<>() : new LinkedHashMap<>(attributes));
    }

    public String getName() {
        return name;
    }

    public long getTimeUnixNano() {
        return timeUnixNano;
    }

    public Map<String, Object> getAttributes() {
        return attributes;
    }
}
