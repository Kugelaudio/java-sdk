package com.kugelaudio.sdk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.kugelaudio.sdk.internal.Diagnostics;
import com.kugelaudio.sdk.internal.Errors;
import com.kugelaudio.sdk.internal.WsUrlBuilder;
import com.kugelaudio.sdk.internal.WsConnector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.*;
import java.util.concurrent.*;

/**
 * Multi-context streaming session supporting up to 20 concurrent speakers over a single WebSocket.
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
    private final WsConnector connector = new WsConnector();
    private final MultiContextConfig config;
    private volatile WebSocket ws;
    private volatile String sessionId;
    private volatile boolean closed;
    /** Per-context usage (KUG-1192): each context is its own conversation, so
     * usage arrives on that context's {@code context_closed} frame. */
    private final Map<String, SessionUsage> contextUsage = new ConcurrentHashMap<>();
    private final Set<String> activeContexts = ConcurrentHashMap.newKeySet();
    private MultiContextCallbacks callbacks;
    /**
     * Generation-param overrides set via {@link #updateSettings(SettingsUpdate)}
     * (KUG-1166), re-applied as session-level fields on every {@code createContext}
     * so a context opened after the update inherits them ({@link MultiContextConfig}
     * is immutable).
     */
    private final ObjectNode settingsOverrides = MAPPER.createObjectNode();
    /** Completed by the listener when the server acks an {@code update_settings}. */
    private volatile CompletableFuture<EffectiveSettings> settingsFuture;

    private final Diagnostics diagnostics;
    /**
     * Diagnostics operation of each context's turn in flight. Per the
     * contract's "Operation scope", a turn is one context's text up to its
     * {@code final} frame, an error or a cancellation; one context's failure
     * or barge-in never touches another's. Connecting is its own operation.
     */
    private final Map<String, Diagnostics.Operation> turns = new ConcurrentHashMap<>();

    MultiContextSession(KugelAudioOptions options, HttpClient httpClient, MultiContextConfig config,
                        Diagnostics diagnostics) {
        this.options = options;
        this.httpClient = httpClient;
        this.config = config;
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
    }

    /**
     * Opens the WebSocket connection.
     */
    public void connect(MultiContextCallbacks callbacks) {
        this.callbacks = Objects.requireNonNull(callbacks);
        String wsUrl = WsUrlBuilder.build(options, "/ws/tts/multi");
        Diagnostics.Operation op = newOperation().stage(Diagnostics.STAGE_HANDSHAKE);
        try {
            MultiContextListener listener = new MultiContextListener();
            ws = connector.connect(httpClient, URI.create(wsUrl), listener, options.getTimeout());
            op.success();
        } catch (Exception e) {
            KugelAudioException typed = Errors.classifyWsHandshake(e);
            KugelAudioException wrapped = typed != null
                    ? typed
                    : new ConnectionException(
                            "Failed to connect multi-context session: " + e.getMessage(), e);
            op.fail(wrapped);
            throw wrapped;
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
        if (config.getOutputFormat() != null) msg.put("output_format", config.getOutputFormat());
        if (config.getNormalize() != null) msg.put("normalize", config.getNormalize());
        if (config.getLanguage() != null) msg.put("language", config.getLanguage());
        if (config.getWordTimestamps() != null && config.getWordTimestamps()) {
            msg.put("word_timestamps", true);
        }
        if (config.getTemperature() != null) msg.put("temperature", config.getTemperature());
        if (config.getProjectId() != null) msg.put("project_id", config.getProjectId());
        // An empty list is meaningful (explicit opt-out) and must be sent;
        // only null (use the project default) is omitted.
        if (config.getDictionaryIds() != null) {
            msg.putPOJO("dictionary_ids", config.getDictionaryIds());
        }
        // Re-apply mid-connection updateSettings() overrides so a context opened
        // after the update inherits the new session-level params (KUG-1166).
        settingsOverrides.fields().forEachRemaining(
                e -> msg.set(e.getKey(), e.getValue().deepCopy()));

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
        if (contextId != null && text != null && !text.isEmpty()) {
            turns.computeIfAbsent(contextId,
                    id -> newOperation().stage(Diagnostics.STAGE_AWAITING_FIRST_AUDIO));
        }
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

    /** Closes a specific context, letting any queued sentences finish first. */
    public void closeContext(String contextId) {
        closeContext(contextId, false);
    }

    /**
     * Closes a specific context.
     *
     * @param contextId the context to close
     * @param immediate when {@code true}, <b>barge-in</b>: the server cancels the
     *     context's in-flight generation immediately and discards any buffered or
     *     queued text instead of draining it. Use this when the end user speaks
     *     over the agent. When {@code false}, queued sentences finish first.
     */
    public void closeContext(String contextId, boolean immediate) {
        ensureConnected();
        ObjectNode msg = MAPPER.createObjectNode();
        msg.put("close_context", true);
        msg.put("context_id", contextId);
        if (immediate) msg.put("immediate", true);
        sendJson(msg);
        activeContexts.remove(contextId);
        // A barge-in cancels only this context's turn; a graceful close lets
        // it finish and end at its final frame.
        if (immediate) endTurn(contextId, Diagnostics.Operation::cancelled);
    }

    /** Sends a keep-alive ping for a context to prevent inactivity timeout. */
    public void keepAlive(String contextId) {
        ensureConnected();
        ObjectNode msg = MAPPER.createObjectNode();
        msg.put("keep_alive", true);
        msg.put("context_id", contextId);
        sendJson(msg);
    }

    /**
     * Changes the session's generation parameters mid-connection (KUG-1166).
     *
     * <p>Session-scoped (there is no {@code contextId}): the update applies to
     * contexts started <b>after</b> it — a context already streaming keeps the
     * settings it began with, since generation parameters are bound when a
     * context's engine session opens. With the common one-context-per-turn
     * pattern that means it takes effect on the next turn. Per-context
     * {@code cfgScale} / {@code maxNewTokens} passed to
     * {@link #createContext(String, CreateContextOptions)} still win for that
     * context.
     *
     * <p>Blocks until the server's {@code settings_updated} acknowledgement.
     *
     * @param settings the generation parameters to change (at least one)
     * @return the generation parameters now in effect (the server's echo)
     * @throws IllegalArgumentException if {@code settings} carries no field
     * @throws KugelAudioException if not connected, the server rejects the
     *     update, or the acknowledgement does not arrive in time
     */
    public EffectiveSettings updateSettings(SettingsUpdate settings) {
        ObjectNode body = StreamingSession.buildSettingsBody(settings);
        if (ws == null) {
            throw new KugelAudioException("MultiContextSession not connected. Call connect() first.");
        }
        CompletableFuture<EffectiveSettings> ackFuture = new CompletableFuture<>();
        settingsFuture = ackFuture;
        try {
            ObjectNode msg = MAPPER.createObjectNode();
            msg.set("update_settings", body);
            ws.sendText(MAPPER.writeValueAsString(msg), true).join();
            EffectiveSettings effective = ackFuture.get(options.getTimeout().toSeconds(), TimeUnit.SECONDS);
            body.fields().forEachRemaining(
                    e -> settingsOverrides.set(e.getKey(), e.getValue().deepCopy()));
            return effective;
        } catch (TimeoutException te) {
            throw new KugelAudioException(
                    "Timed out waiting for settings_updated acknowledgement", te);
        } catch (ExecutionException ee) {
            Throwable cause = ee.getCause();
            if (cause instanceof KugelAudioException) throw (KugelAudioException) cause;
            throw new KugelAudioException("update_settings failed: " + cause, cause);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new KugelAudioException("Interrupted waiting for settings_updated", ie);
        } catch (KugelAudioException ke) {
            throw ke;
        } catch (Exception e) {
            throw new KugelAudioException("Failed to send update_settings: " + e.getMessage(), e);
        } finally {
            settingsFuture = null;
        }
    }

    /** Completes the in-flight {@link #updateSettings} wait from a server frame. */
    private void onSettingsFrame(JsonNode json) {
        CompletableFuture<EffectiveSettings> f = settingsFuture;
        if (f == null) return;
        if (json.path("settings_updated").asBoolean(false)) {
            f.complete(EffectiveSettings.fromJson(json.get("settings")));
        } else if (json.has("error")) {
            f.completeExceptionally(TTSResource.mapWsError(json));
        }
    }

    /** Returns the server-assigned session ID, or null if not yet assigned. */
    public String getSessionId() { return sessionId; }

    /**
     * Per-context usage (audio time + amount charged) for a closed context, or
     * {@code null} if that context hasn't closed yet. Each context is its own
     * conversation — read this (e.g. inside your {@code onContextClosed}
     * callback) to bill per conversation. See {@link SessionUsage}.
     */
    public SessionUsage getUsageFor(String contextId) { return contextUsage.get(contextId); }

    /** Snapshot of context_id → per-context usage for all closed contexts. */
    public Map<String, SessionUsage> getContextUsage() {
        return new HashMap<>(contextUsage);
    }

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
        // Cancels any handshake still in flight and hands back the published
        // socket (if any) for the graceful close below.
        WebSocket owned = connector.close();
        if (owned != null) ws = owned;
        // Closing the session aborts every turn still in flight: a
        // caller-initiated cancellation, never a failure.
        for (String contextId : new ArrayList<>(turns.keySet())) {
            endTurn(contextId, Diagnostics.Operation::cancelled);
        }
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

    /** Sends a frame. A send failure means the socket is gone: every open turn fails. */
    private void sendJson(ObjectNode msg) {
        try {
            String json = MAPPER.writeValueAsString(msg);
            LOG.debug("MultiContextSession send: {}", json);
            ws.sendText(json, true).join();
        } catch (Exception e) {
            KugelAudioException wrapped =
                    new KugelAudioException("Failed to send message: " + e.getMessage(), e);
            failTurns(null, wrapped, false, null);
            throw wrapped;
        }
    }

    private Diagnostics.Operation newOperation() {
        return diagnostics.startOperation(Diagnostics.OP_MULTI_CONTEXT, Diagnostics.TRANSPORT_WEBSOCKET);
    }

    /** Ends {@code contextId}'s open turn, if any, with {@code terminal}. */
    private void endTurn(String contextId, java.util.function.Consumer<Diagnostics.Operation> terminal) {
        if (contextId == null) return;
        Diagnostics.Operation op = turns.remove(contextId);
        if (op != null) terminal.accept(op);
    }

    /**
     * Fails the turn of {@code contextId}, or every open turn when the failure
     * is session-wide ({@code contextId == null}): {@code rejected} for a
     * server error frame or error close code, a transport failure otherwise.
     * A failure with no matching open turn is reported on an operation of its own.
     */
    private void failTurns(String contextId, KugelAudioException error, boolean rejected,
                           Integer wsCloseCode) {
        List<Diagnostics.Operation> failed = new ArrayList<>();
        if (contextId != null) {
            Diagnostics.Operation op = turns.remove(contextId);
            if (op != null) failed.add(op);
        } else {
            for (String id : new ArrayList<>(turns.keySet())) {
                Diagnostics.Operation op = turns.remove(id);
                if (op != null) failed.add(op);
            }
        }
        if (failed.isEmpty()) {
            failed.add(newOperation().stage(Diagnostics.STAGE_AWAITING_FIRST_AUDIO));
        }
        for (Diagnostics.Operation op : failed) {
            if (wsCloseCode != null) op.wsCloseCode(wsCloseCode);
            if (rejected) op.rejected(error);
            else op.fail(error);
        }
    }

    /**
     * Internal listener for the /ws/tts/multi endpoint.
     */
    final class MultiContextListener implements WebSocket.Listener {
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
                    // Reject a pending updateSettings() before the generic
                    // onError sink fires (KUG-1166).
                    onSettingsFrame(json);
                    KugelAudioException frameError = TTSResource.mapWsError(json);
                    failTurns(ctxId, frameError, true, null);
                    callbacks.onError(ctxId, frameError);
                    webSocket.request(1);
                    return null;
                }

                if (json.path("settings_updated").asBoolean(false)) {
                    onSettingsFrame(json);
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
                    String enc = json.path("enc").asText(AudioChunk.PCM_S16LE);
                    int idx = json.path("idx").asInt(0);
                    int sr = json.path("sr").asInt(24000);
                    int samples = json.path("samples").asInt(0);
                    AudioChunk chunk = AudioChunk.fromServerMessage(audioBase64, enc, idx, sr, samples);
                    Diagnostics.Operation op = turns.get(ctxId);
                    if (op != null) op.chunk(chunk.getAudio() == null ? 0 : chunk.getAudio().length);
                    callbacks.onChunk(ctxId, chunk);
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

                if (json.path("final").asBoolean(false)) {
                    // Per-context end-of-audio marker (KUG-1238): the turn succeeded.
                    endTurn(ctxId, Diagnostics.Operation::success);
                }

                if (json.path("context_closed").asBoolean(false)) {
                    // A graceful close sends final first; this is the backstop.
                    endTurn(ctxId, Diagnostics.Operation::success);
                    activeContexts.remove(ctxId);
                    // Per-context (per-conversation) usage rides on context_closed.
                    SessionUsage usage = SessionUsage.fromSessionClosed(json);
                    if (usage != null && ctxId != null) contextUsage.put(ctxId, usage);
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
            // Mirror Python/JS: only server-initiated error codes (4001/4003/
            // 4029/4500) surface as errors. Normal/going-away/abnormal etc.
            // are end-of-session.
            if (Errors.isErrorCloseCode(statusCode)) {
                KugelAudioException closeError = TTSResource.mapWsCloseCode(statusCode, reason);
                failTurns(null, closeError, true, statusCode);
                callbacks.onError(null, closeError);
            }
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            ConnectionException wrapped =
                    new ConnectionException("WebSocket error: " + error.getMessage(), error);
            failTurns(null, wrapped, false, null);
            callbacks.onError(null, wrapped);
        }
    }
}
