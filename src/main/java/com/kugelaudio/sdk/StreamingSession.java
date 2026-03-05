package com.kugelaudio.sdk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.kugelaudio.sdk.internal.WsUrlBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/**
 * A streaming session for incremental text-to-speech.
 * Text is sent token-by-token (e.g. from an LLM), and audio is received via callbacks.
 *
 * <pre>{@code
 * try (StreamingSession session = client.tts().streamingSession(config, callbacks)) {
 *     session.send("Hello ");
 *     session.send("world!");
 *     session.flush();
 * }
 * }</pre>
 */
public final class StreamingSession implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(StreamingSession.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final KugelAudioOptions options;
    private final HttpClient httpClient;
    private final StreamConfig config;
    private final StreamCallbacks callbacks;
    private volatile WebSocket ws;
    private volatile boolean configSent;
    private volatile boolean closed;

    StreamingSession(KugelAudioOptions options, HttpClient httpClient, StreamConfig config, StreamCallbacks callbacks) {
        this.options = options;
        this.httpClient = httpClient;
        this.config = config;
        this.callbacks = callbacks;
    }

    /**
     * Opens the WebSocket connection. Must be called before sending text.
     */
    public void connect() {
        if (ws != null) return;
        String wsUrl = WsUrlBuilder.build(options, "/ws/tts/stream");
        try {
            SessionListener listener = new SessionListener(callbacks);
            ws = httpClient.newWebSocketBuilder()
                    .buildAsync(URI.create(wsUrl), listener)
                    .get(options.getTimeout().toSeconds(), TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new ConnectionException("Failed to connect streaming session: " + e.getMessage(), e);
        }
    }

    /**
     * Sends a text fragment to the TTS engine.
     * The first message automatically includes the session config (voice, model, etc.).
     */
    public void send(String text) {
        send(text, false);
    }

    /**
     * Sends a text fragment and optionally triggers generation.
     *
     * @param text  Text to append
     * @param flush If true, the server will immediately start generating audio for all buffered text
     */
    public void send(String text, boolean flush) {
        ensureConnected();
        ObjectNode msg = MAPPER.createObjectNode();
        msg.put("text", text);
        if (flush) msg.put("flush", true);

        if (!configSent) {
            TTSResource.warnIfNoLanguage(config.getLanguage(), config.getNormalize());
            if (config.getVoiceId() != null) msg.put("voice_id", config.getVoiceId());
            if (config.getModelId() != null) msg.put("model_id", config.getModelId());
            if (config.getCfgScale() != null) msg.put("cfg_scale", config.getCfgScale());
            if (config.getMaxNewTokens() != null) msg.put("max_new_tokens", config.getMaxNewTokens());
            if (config.getSampleRate() != null) msg.put("sample_rate", config.getSampleRate());
            if (config.getNormalize() != null) msg.put("normalize", config.getNormalize());
            if (config.getLanguage() != null) msg.put("language", config.getLanguage());
            if (config.getFlushTimeoutMs() != null) msg.put("flush_timeout_ms", config.getFlushTimeoutMs());
            if (config.getWordTimestamps() != null && config.getWordTimestamps()) {
                msg.put("word_timestamps", true);
            }
            configSent = true;
        }

        sendJson(msg);
    }

    /** Flushes all buffered text, triggering audio generation. */
    public void flush() {
        ensureConnected();
        ObjectNode msg = MAPPER.createObjectNode();
        msg.put("flush", true);
        sendJson(msg);
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        if (ws != null) {
            try {
                ObjectNode closeMsg = MAPPER.createObjectNode();
                closeMsg.put("close", true);
                ws.sendText(MAPPER.writeValueAsString(closeMsg), true).join();
                ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
            } catch (Exception ignored) {}
        }
    }

    private void ensureConnected() {
        if (ws == null) {
            connect();
        }
        if (closed) {
            throw new KugelAudioException("Session is closed");
        }
    }

    private void sendJson(ObjectNode msg) {
        try {
            String json = MAPPER.writeValueAsString(msg);
            LOG.debug("StreamingSession send: {}", json);
            ws.sendText(json, true).join();
        } catch (Exception e) {
            throw new KugelAudioException("Failed to send message: " + e.getMessage(), e);
        }
    }

    /**
     * Internal listener for the /ws/tts/stream endpoint.
     */
    private static final class SessionListener implements WebSocket.Listener {
        private final StreamCallbacks callbacks;
        private final StringBuilder textBuffer = new StringBuilder();

        SessionListener(StreamCallbacks callbacks) {
            this.callbacks = callbacks;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            textBuffer.append(data);
            if (!last) {
                webSocket.request(1);
                return null;
            }
            String msg = textBuffer.toString();
            textBuffer.setLength(0);

            try {
                JsonNode json = MAPPER.readTree(msg);

                if (json.has("error")) {
                    callbacks.onError(TTSResource.mapWsError(json.get("error").asText()));
                    webSocket.request(1);
                    return null;
                }

                if (json.has("audio") && !json.get("audio").isNull()) {
                    String audioBase64 = json.get("audio").asText();
                    int idx = json.path("idx").asInt(0);
                    int sr = json.path("sr").asInt(24000);
                    int samples = json.path("samples").asInt(0);
                    callbacks.onChunk(AudioChunk.fromServerMessage(audioBase64, idx, sr, samples));
                }

                if (json.has("word_timestamps")) {
                    List<WordTimestamp> timestamps = new ArrayList<>();
                    for (JsonNode wt : json.get("word_timestamps")) {
                        timestamps.add(new WordTimestamp(
                                wt.path("word").asText(),
                                wt.path("start_ms").asLong(),
                                wt.path("end_ms").asLong(),
                                wt.path("char_start").asInt(),
                                wt.path("char_end").asInt(),
                                wt.path("score").asDouble()
                        ));
                    }
                    callbacks.onWordTimestamps(timestamps);
                }

            } catch (Exception e) {
                callbacks.onError(new KugelAudioException("Error processing message: " + e.getMessage(), e));
            }

            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            if (statusCode != WebSocket.NORMAL_CLOSURE) {
                callbacks.onError(TTSResource.mapWsCloseCode(statusCode, reason));
            }
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            callbacks.onError(new ConnectionException("WebSocket error: " + error.getMessage(), error));
        }
    }
}
