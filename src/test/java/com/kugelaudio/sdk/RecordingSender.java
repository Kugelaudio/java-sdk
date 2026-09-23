package com.kugelaudio.sdk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kugelaudio.sdk.internal.Diagnostics;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Test double for {@link Diagnostics.Sender}: records every
 * {@code (url, headers, payload)} instead of performing network I/O.
 */
final class RecordingSender implements Diagnostics.Sender {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    record Call(String url, Map<String, String> headers, byte[] body) {
        String bodyText() {
            return new String(body, StandardCharsets.UTF_8);
        }

        JsonNode json() {
            try {
                return MAPPER.readTree(body);
            } catch (Exception e) {
                throw new AssertionError("payload is not valid JSON: " + bodyText(), e);
            }
        }
    }

    final List<Call> calls = new CopyOnWriteArrayList<>();
    private final RuntimeException failure;
    private final int status;

    RecordingSender() {
        this(null, 202);
    }

    RecordingSender(RuntimeException failure, int status) {
        this.failure = failure;
        this.status = status;
    }

    /** A sender that answers every batch with {@code status}. */
    static RecordingSender responding(int status) {
        return new RecordingSender(null, status);
    }

    /** A sender that always blows up, to prove failures never reach the caller. */
    static RecordingSender alwaysFailing() {
        return new RecordingSender(new IllegalStateException("telemetry endpoint is on fire"), 0);
    }

    @Override
    public int send(String url, Map<String, String> headers, byte[] body) {
        calls.add(new Call(url, Map.copyOf(headers), body));
        if (failure != null) throw failure;
        return status;
    }

    /** The log records of the single recorded batch. */
    List<JsonNode> records() {
        if (calls.size() != 1) {
            throw new AssertionError("expected exactly 1 delivery, got " + calls.size());
        }
        return recordsOf(calls.get(0));
    }

    /** The log records of every recorded batch, in delivery order. */
    List<JsonNode> allRecords() {
        List<JsonNode> out = new ArrayList<>();
        for (Call call : calls) out.addAll(recordsOf(call));
        return out;
    }

    /** The log records of one recorded batch. */
    static List<JsonNode> recordsOf(Call call) {
        List<JsonNode> out = new ArrayList<>();
        call.json()
                .path("resourceLogs").get(0)
                .path("scopeLogs").get(0)
                .path("logRecords")
                .forEach(out::add);
        return out;
    }

    /** Flattened attributes of one log record, keyed by attribute name. */
    static Map<String, String> attributesOf(JsonNode record) {
        Map<String, String> attrs = new LinkedHashMap<>();
        for (JsonNode attr : record.path("attributes")) {
            JsonNode value = attr.path("value");
            String text = value.has("stringValue")
                    ? value.path("stringValue").asText()
                    : value.path("intValue").asText();
            attrs.put(attr.path("key").asText(), text);
        }
        return attrs;
    }
}
