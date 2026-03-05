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
import java.util.*;
import java.util.concurrent.*;

/**
 * Multi-context streaming session supporting up to 5 concurrent speakers over a single WebSocket.
 *
 * <pre>{@code
 * try (MultiContextSession session = client.tts().multiContextSession(config)) {
 *     session.connect(callbacks);
 *     session.createContext("narrator", CreateContextOptions.builder().voiceId(100).build());
 *     session.createContext("character", CreateContextOptions.builder().voiceId(200).build());
 *     session.send("narrator", "Once upon a time...", true);
 *     session.send("character", "Hello!", true);
 *     session.closeContext("narrator");
 *     session.closeContext("character");
 * }
 * }</pre>
 */
public final class MultiContextSession implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(MultiContextSession.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final KugelAudioOptions options;
    private final HttpClient httpClient;
    private final MultiContextConfig config;
    private volatile WebSocket ws;
    private volatile String sessionId;
    private volatile boolean closed;
    private final Set<String> activeContexts = ConcurrentHashMap.newKeySet();
    private MultiContextCallbacks callbacks;

    MultiContextSession(KugelAudioOptions options, HttpClient httpClient, MultiContextConfig config) {
        this.options = options;
        this.httpClient = httpClient;
        this.config = config;
    }

    /**
     * Opens the WebSocket connection.
     */
    public void connect(MultiContextCallbacks callbacks) {
        this.callbacks = Objects.requireNonNull(callbacks);
        String wsUrl = WsUrlBuilder.build(options, "/ws/tts/multi");
        try {
            MultiContextListener listener = new MultiContextListener();
            ws = httpClient.newWebSocketBuilder()
                    .buildAsync(URI.create(wsUrl), listener)
                    .get(options.getTimeout().toSeconds(), TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new ConnectionException("Failed to connect multi-context session: " + e.getMessage(), e);
        }
    }

    /**
     * Creates a new audio context (speaker) in the session.
     */
    public void createContext(String contextId) {
        createContext(contextId, null);
    }

    /**
     * Creates a new audio context with specific voice settings.
     */
    public void createContext(String contextId, CreateContextOptions contextOptions) {
        ensureConnected();
        ObjectNode msg = MAPPER.createObjectNode();
        msg.put("text", " ");
        msg.put("context_id", contextId);

        TTSResource.warnIfNoLanguage(config.getLanguage(), config.getNormalize());
        if (config.getSampleRate() != null) msg.put("sample_rate", config.getSampleRate());
        if (config.getNormalize() != null) msg.put("normalize", config.getNormalize());
        if (config.getLanguage() != null) msg.put("language", config.getLanguage());
        if (config.getWordTimestamps() != null && config.getWordTimestamps()) {
            msg.put("word_timestamps", true);
        }

        if (contextOptions != null) {
            ObjectNode voiceSettings = MAPPER.createObjectNode();
            if (contextOptions.getVoiceId() != null) voiceSettings.put("voice_id", contextOptions.getVoiceId());
            if (contextOptions.getCfgScale() != null) voiceSettings.put("cfg_scale", contextOptions.getCfgScale());
            if (contextOptions.getMaxNewTokens() != null) voiceSettings.put("max_new_tokens", contextOptions.getMaxNewTokens());
            if (voiceSettings.size() > 0) msg.set("voice_settings", voiceSettings);
        }

        activeContexts.add(contextId);
        sendJson(msg);
    }

    /** Sends text to a specific context. */
    public void send(String contextId, String text) {
        send(contextId, text, false);
    }

    /** Sends text to a specific context, optionally flushing to trigger generation. */
    public void send(String contextId, String text, boolean flush) {
        ensureConnected();
        ObjectNode msg = MAPPER.createObjectNode();
        msg.put("text", text);
        msg.put("context_id", contextId);
        if (flush) msg.put("flush", true);
        sendJson(msg);
    }

    /** Flushes a context's buffer, triggering audio generation. */
    public void flush(String contextId) {
        ensureConnected();
        ObjectNode msg = MAPPER.createObjectNode();
        msg.put("flush", true);
        msg.put("context_id", contextId);
        sendJson(msg);
    }

    /** Closes a specific context. */
    public void closeContext(String contextId) {
        ensureConnected();
        ObjectNode msg = MAPPER.createObjectNode();
        msg.put("close_context", true);
        msg.put("context_id", contextId);
        sendJson(msg);
        activeContexts.remove(contextId);
    }

    /** Sends a keep-alive ping for a context to prevent inactivity timeout. */
    public void keepAlive(String contextId) {
        ensureConnected();
        ObjectNode msg = MAPPER.createObjectNode();
        msg.put("keep_alive", true);
        msg.put("context_id", contextId);
        sendJson(msg);
    }

    /** Returns the server-assigned session ID, or null if not yet assigned. */
    public String getSessionId() { return sessionId; }

    /** Returns the set of currently active context IDs. */
    public Set<String> getActiveContexts() { return Collections.unmodifiableSet(activeContexts); }

    /** Returns true if the WebSocket connection is active. */
    public boolean isConnected() {
        return ws != null && !ws.isInputClosed() && !ws.isOutputClosed() && !closed;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        if (ws != null) {
            try {
                ObjectNode closeMsg = MAPPER.createObjectNode();
                closeMsg.put("close_socket", true);
                ws.sendText(MAPPER.writeValueAsString(closeMsg), true).join();
                ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
            } catch (Exception ignored) {}
        }
        activeContexts.clear();
    }

    private void ensureConnected() {
        if (ws == null) {
            throw new KugelAudioException("Session not connected. Call connect() first.");
        }
        if (closed) {
            throw new KugelAudioException("Session is closed");
        }
    }

    private void sendJson(ObjectNode msg) {
        try {
            String json = MAPPER.writeValueAsString(msg);
            LOG.debug("MultiContextSession send: {}", json);
            ws.sendText(json, true).join();
        } catch (Exception e) {
            throw new KugelAudioException("Failed to send message: " + e.getMessage(), e);
        }
    }

    /**
     * Internal listener for the /ws/tts/multi endpoint.
     */
    private final class MultiContextListener implements WebSocket.Listener {
        private final StringBuilder textBuffer = new StringBuilder();

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
                String ctxId = json.path("context_id").asText(null);

                if (json.has("error")) {
                    callbacks.onError(ctxId, TTSResource.mapWsError(json.get("error").asText()));
                    webSocket.request(1);
                    return null;
                }

                if (json.path("session_started").asBoolean(false)) {
                    sessionId = json.path("session_id").asText(null);
                }

                if (json.path("context_created").asBoolean(false)) {
                    callbacks.onContextCreated(ctxId);
                }

                if (json.path("generation_started").asBoolean(false)) {
                    callbacks.onGenerationStarted(ctxId);
                }

                if (json.has("audio") && !json.get("audio").isNull() && ctxId != null) {
                    String audioBase64 = json.get("audio").asText();
                    int idx = json.path("idx").asInt(0);
                    int sr = json.path("sr").asInt(24000);
                    int samples = json.path("samples").asInt(0);
                    callbacks.onChunk(ctxId, AudioChunk.fromServerMessage(audioBase64, idx, sr, samples));
                }

                if (json.has("word_timestamps") && ctxId != null) {
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
                    callbacks.onWordTimestamps(ctxId, timestamps);
                }

                if (json.path("is_final").asBoolean(false)) {
                    callbacks.onContextComplete(ctxId);
                }

                if (json.path("context_closed").asBoolean(false)) {
                    activeContexts.remove(ctxId);
                    callbacks.onContextClosed(ctxId);
                }

                if (json.path("session_closed").asBoolean(false)) {
                    callbacks.onSessionClosed();
                }

            } catch (Exception e) {
                callbacks.onError(null, new KugelAudioException("Error processing message: " + e.getMessage(), e));
            }

            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            if (statusCode != WebSocket.NORMAL_CLOSURE) {
                callbacks.onError(null, TTSResource.mapWsCloseCode(statusCode, reason));
            }
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            callbacks.onError(null, new ConnectionException("WebSocket error: " + error.getMessage(), error));
        }
    }
}
