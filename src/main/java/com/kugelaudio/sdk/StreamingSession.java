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
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A streaming session for incremental text-to-speech over {@code /ws/tts/stream}.
 *
 * <p>Forward raw LLM tokens one-by-one via {@link #send(String)}; the server accumulates
 * them internally and starts generation only at natural sentence boundaries. A single
 * {@link #flush()} at the end of the turn emits any trailing text.
 *
 * <pre>{@code
 * try (StreamingSession session = client.streamingSession(config, callbacks)) {
 *     for (String token : llmTokens) {
 *         session.send(token);          // flush=false — let the server decide boundaries
 *     }
 *     session.flush();                  // emit the final trailing fragment
 * }
 * }</pre>
 *
 * <p><b>Avoid per-sentence flushing.</b> Calling {@code send(text, true)} or {@link #flush()}
 * between individual sentences or words creates a hard turn boundary on the server. Even though
 * the KV cache is preserved across boundaries, there is a perceptible silence gap while the
 * client issues the next send and the model begins generating again. Word-level flushing has
 * been measured at <b>3–5× higher latency</b> per segment versus sentence-level, and
 * sentence-level is 2–3× slower than flushing only at the end of the full turn.
 */
public final class StreamingSession implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(StreamingSession.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final KugelAudioOptions options;
    private final HttpClient httpClient;
    private final WsConnector connector = new WsConnector();
    private final StreamConfig config;
    private final StreamCallbacks callbacks;
    private final CountDownLatch sessionClosedLatch = new CountDownLatch(1);
    private volatile WebSocket ws;
    private volatile boolean configSent;
    private volatile boolean closed;
    /** Typed per-session usage from the most recently closed session (KUG-1192). */
    private volatile SessionUsage lastUsage;
    /**
     * Completed by the listener when the server acks a barge-in
     * ({@code {"interrupted": true}}). Set per {@link #cancelCurrent()} call so
     * that method can block until the ack arrives; {@code null} otherwise.
     */
    private volatile CompletableFuture<Void> interruptedFuture;
    /**
     * Generation-param overrides set via {@link #updateSettings(SettingsUpdate)}
     * (KUG-1166). Re-applied on every config re-send so a change survives an
     * {@link #endSession()} + {@link #send(String)} cycle — {@link StreamConfig}
     * is immutable, so the override cannot live on it.
     */
    private final ObjectNode settingsOverrides = MAPPER.createObjectNode();
    /**
     * Completed by the listener when the server acks an {@code update_settings}
     * ({@code {"settings_updated": true, ...}}) or rejects it. Set per
     * {@link #updateSettings(SettingsUpdate)} call; {@code null} otherwise.
     */
    private volatile CompletableFuture<EffectiveSettings> settingsFuture;

    private final Diagnostics diagnostics;
    /**
     * Diagnostics operation of the turn in flight, or {@code null} between
     * turns. Per the contract's "Operation scope", every turn is its own
     * operation: it starts with the first text after the previous turn ended
     * and ends at its final frame, an error or a cancellation, so a barge-in
     * never silences the failures of later turns. Establishing the connection
     * is a separate operation (see {@link #connect()}).
     */
    private final AtomicReference<Diagnostics.Operation> turn = new AtomicReference<>();

    StreamingSession(KugelAudioOptions options, HttpClient httpClient, StreamConfig config,
                     StreamCallbacks callbacks, Diagnostics diagnostics) {
        this.options = options;
        this.httpClient = httpClient;
        this.config = config;
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        this.callbacks = wrapCallbacks(callbacks);
    }

    private StreamCallbacks wrapCallbacks(StreamCallbacks user) {
        return new StreamCallbacks() {
            @Override public void onChunk(AudioChunk chunk) {
                Diagnostics.Operation op = turn.get();
                if (op != null) op.chunk(chunk.getAudio() == null ? 0 : chunk.getAudio().length);
                user.onChunk(chunk);
            }
            @Override public void onGenerationStarted(int chunkId, String text) {
                user.onGenerationStarted(chunkId, text);
            }
            @Override public void onChunkComplete(int chunkId, double audioSeconds, double genMs) {
                user.onChunkComplete(chunkId, audioSeconds, genMs);
            }
            @Override public void onWordTimestamps(List<WordTimestamp> timestamps) {
                user.onWordTimestamps(timestamps);
            }
            @Override public void onInterrupted() {
                try { user.onInterrupted(); }
                finally { signalInterrupted(); }
            }
            @Override public void onSessionClosed(double total, int textChunks, int audioChunks) {
                endTurn(Diagnostics.Operation::success);
                try { user.onSessionClosed(total, textChunks, audioChunks); }
                finally { sessionClosedLatch.countDown(); }
            }
            @Override public void onError(KugelAudioException e) {
                // Diagnostics are recorded at each error site in SessionListener,
                // where a server rejection and a transport failure are distinguishable.
                user.onError(e);
            }
        };
    }

    /** The open turn's operation, opening one if the previous turn ended. */
    private Diagnostics.Operation beginTurn() {
        Diagnostics.Operation current = turn.get();
        if (current != null) return current;
        Diagnostics.Operation fresh = diagnostics
                .startOperation(Diagnostics.OP_STREAM_SESSION, Diagnostics.TRANSPORT_WEBSOCKET)
                .stage(Diagnostics.STAGE_AWAITING_FIRST_AUDIO);
        return turn.compareAndSet(null, fresh) ? fresh : beginTurn();
    }

    /** Ends the open turn, if any, with {@code terminal}. */
    private void endTurn(java.util.function.Consumer<Diagnostics.Operation> terminal) {
        Diagnostics.Operation op = turn.getAndSet(null);
        if (op != null) terminal.accept(op);
    }

    /**
     * Fails the open turn: {@code rejected} for a server error frame or error
     * close code, a transport failure otherwise. A failure while no turn is
     * open (the socket failed between turns) is reported on an operation of
     * its own.
     */
    private void failTurn(KugelAudioException error, boolean rejected, Integer wsCloseCode) {
        Diagnostics.Operation op = turn.getAndSet(null);
        if (op == null) {
            op = diagnostics
                    .startOperation(Diagnostics.OP_STREAM_SESSION, Diagnostics.TRANSPORT_WEBSOCKET)
                    .stage(Diagnostics.STAGE_AWAITING_FIRST_AUDIO);
        }
        if (wsCloseCode != null) op.wsCloseCode(wsCloseCode);
        if (rejected) op.rejected(error);
        else op.fail(error);
    }

    /** Completes the in-flight {@link #cancelCurrent()} wait, if any. */
    private void signalInterrupted() {
        CompletableFuture<Void> f = interruptedFuture;
        if (f != null) f.complete(null);
    }

    /**
     * Per-session usage from the most recently closed session, or {@code null}
     * before the first session closes. Use this to bill your own customers per
     * conversation. See {@link SessionUsage}.
     */
    public SessionUsage getLastUsage() {
        return lastUsage;
    }

    /**
     * Opens the WebSocket connection. Must be called before sending text.
     */
    public void connect() {
        if (ws != null) return;
        String wsUrl = WsUrlBuilder.build(options, "/ws/tts/stream");
        // Establishing the connection is its own operation (contract "Operation scope").
        Diagnostics.Operation op = diagnostics
                .startOperation(Diagnostics.OP_STREAM_SESSION, Diagnostics.TRANSPORT_WEBSOCKET)
                .stage(Diagnostics.STAGE_HANDSHAKE);
        try {
            ws = connector.connect(httpClient, URI.create(wsUrl), new SessionListener(), options.getTimeout());
            op.success();
        } catch (Exception e) {
            KugelAudioException typed = Errors.classifyWsHandshake(e);
            KugelAudioException wrapped = typed != null
                    ? typed
                    : new ConnectionException(
                            "Failed to connect streaming session: " + e.getMessage(), e);
            op.fail(wrapped);
            throw wrapped;
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
     * Sends a text fragment and optionally triggers immediate generation.
     *
     * @param text  Text to append
     * @param flush If {@code true}, the server immediately starts generating audio for all buffered
     *              text. Prefer {@code false} for LLM token streams — let the server accumulate
     *              tokens until a natural sentence boundary. Use {@code true} only when the
     *              complete phrase/sentence is already available and you want to start generation
     *              now rather than waiting for the auto-timeout.
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
            if (config.getTemperature() != null) msg.put("temperature", config.getTemperature());
            if (config.getMaxNewTokens() != null) msg.put("max_new_tokens", config.getMaxNewTokens());
            if (config.getSampleRate() != null) msg.put("sample_rate", config.getSampleRate());
            if (config.getOutputFormat() != null) msg.put("output_format", config.getOutputFormat());
            if (config.getNormalize() != null) msg.put("normalize", config.getNormalize());
            if (config.getLanguage() != null) msg.put("language", config.getLanguage());
            if (config.getFlushTimeoutMs() != null) msg.put("flush_timeout_ms", config.getFlushTimeoutMs());
            if (config.getWordTimestamps() != null && config.getWordTimestamps()) {
                msg.put("word_timestamps", true);
            }
            if (config.getAutoMode() != null) msg.put("auto_mode", config.getAutoMode());
            if (config.getChunkLengthSchedule() != null && !config.getChunkLengthSchedule().isEmpty()) {
                msg.putPOJO("chunk_length_schedule", config.getChunkLengthSchedule());
            }
            if (config.getSpeed() != null) msg.put("speed", config.getSpeed());
            if (config.getProjectId() != null) msg.put("project_id", config.getProjectId());
            // An empty list is meaningful (explicit opt-out) and must be
            // sent; only null (use the project default) is omitted.
            if (config.getDictionaryIds() != null) {
                msg.putPOJO("dictionary_ids", config.getDictionaryIds());
            }
            // Re-apply mid-connection updateSettings() overrides last so they
            // win over the original config and survive an endSession() + send()
            // re-send (KUG-1166).
            settingsOverrides.fields().forEachRemaining(
                    e -> msg.set(e.getKey(), e.getValue().deepCopy()));
            configSent = true;
        }

        beginTurn();
        sendJson(msg);
    }

    /**
     * Changes generation parameters mid-connection without reconnecting (KUG-1166).
     *
     * <p>Sends an {@code update_settings} message and blocks until the server's
     * {@code settings_updated} acknowledgement, returning the generation
     * parameters now in effect. Only the six fields of {@link SettingsUpdate}
     * are updatable; identity / audio-format fields ({@code voiceId},
     * {@code modelId}, {@code sampleRate}, {@code outputFormat},
     * {@code projectId}, {@code dictionaryIds}) are fixed for the connection — change those with a
     * fresh {@link StreamConfig} after {@link #endSession()} instead.
     *
     * <p>The change applies to the <b>next turn</b>: a turn already streaming
     * keeps the settings it started with, so call this between turns.
     *
     * @param settings the generation parameters to change (at least one)
     * @return the generation parameters now in effect (the server's echo)
     * @throws IllegalArgumentException if {@code settings} carries no field
     * @throws KugelAudioException if not connected, the server rejects the
     *     update, or the acknowledgement does not arrive in time
     */
    public EffectiveSettings updateSettings(SettingsUpdate settings) {
        ObjectNode body = buildSettingsBody(settings);
        if (ws == null) {
            throw new KugelAudioException("StreamingSession not connected. Call connect() first.");
        }
        CompletableFuture<EffectiveSettings> ackFuture = new CompletableFuture<>();
        settingsFuture = ackFuture;
        try {
            ObjectNode msg = MAPPER.createObjectNode();
            msg.set("update_settings", body);
            ws.sendText(MAPPER.writeValueAsString(msg), true).join();
            EffectiveSettings effective = ackFuture.get(options.getTimeout().toSeconds(), TimeUnit.SECONDS);
            // Track overrides only after the server accepts the update, so a
            // rejected value cannot poison later config re-sends.
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

    /** Build the snake_case {@code update_settings} body; throws when empty (KUG-1166). */
    static ObjectNode buildSettingsBody(SettingsUpdate s) {
        ObjectNode body = MAPPER.createObjectNode();
        if (s.getCfgScale() != null) body.put("cfg_scale", s.getCfgScale());
        if (s.getTemperature() != null) body.put("temperature", s.getTemperature());
        if (s.getSpeed() != null) body.put("speed", s.getSpeed());
        if (s.getMaxNewTokens() != null) body.put("max_new_tokens", s.getMaxNewTokens());
        if (s.getLanguage() != null) body.put("language", s.getLanguage());
        if (s.getNormalize() != null) body.put("normalize", s.getNormalize());
        if (body.size() == 0) {
            throw new IllegalArgumentException(
                    "updateSettings requires at least one parameter to change "
                    + "(cfgScale, temperature, speed, maxNewTokens, language, normalize)");
        }
        return body;
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

    /** Flushes all buffered text, triggering audio generation. */
    public void flush() {
        ensureConnected();
        ObjectNode msg = MAPPER.createObjectNode();
        msg.put("flush", true);
        sendJson(msg);
    }

    /**
     * Ends the current session but keeps the WebSocket connection open.
     * This allows starting a new session on the same connection, avoiding
     * the overhead of a new WebSocket handshake (~200-300ms).
     * After calling this, use {@link #send} to start the next session.
     */
    public void endSession() {
        if (ws == null) return;
        try {
            ObjectNode closeMsg = MAPPER.createObjectNode();
            closeMsg.put("close", true);
            ws.sendText(MAPPER.writeValueAsString(closeMsg), true).join();
            // Wait for session_closed from server (handled by listener)
        } catch (Exception ignored) {}
        configSent = false;
    }

    /**
     * Interrupts (barges in on) the current generation without closing the socket.
     *
     * <p>Use this when the end user starts speaking over the agent: it tells the
     * server to <b>stop generating audio for the current turn immediately</b> and
     * drop any text that was buffered or queued but not yet spoken. Unlike
     * {@link #endSession()}, no remaining text is flushed — the turn is abandoned.
     *
     * <p>The WebSocket stays open and a fresh session is ready, so the next
     * {@link #send(String)} starts the next user turn immediately (config is
     * re-sent automatically on that first send). This call blocks until the
     * server acknowledges with an {@code interrupted} frame (which also fires
     * {@link StreamCallbacks#onInterrupted()}), or up to 5&nbsp;seconds if the
     * server stays silent.
     *
     * <pre>{@code
     * // VAD detected the user speaking over the agent:
     * session.cancelCurrent();
     * // Socket is still open — start the next turn immediately:
     * session.send(nextText, true);
     * }</pre>
     */
    public void cancelCurrent() {
        if (ws == null) return;
        CompletableFuture<Void> ackFuture = new CompletableFuture<>();
        interruptedFuture = ackFuture;
        try {
            ObjectNode msg = MAPPER.createObjectNode();
            msg.put("cancel", true);
            ws.sendText(MAPPER.writeValueAsString(msg), true).join();
            // Block until the server acks the barge-in, or a quiet 5 s timeout.
            ackFuture.get(5, TimeUnit.SECONDS);
        } catch (TimeoutException te) {
            // Server stayed silent — treat the barge-in as acknowledged.
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception ignored) {
        } finally {
            interruptedFuture = null;
            // A caller-initiated barge-in is not a failure: it only moves the
            // cancellation counter reported in sdk_stats, and it ends only
            // this turn. The next send() opens a fresh operation.
            endTurn(Diagnostics.Operation::cancelled);
            // The server starts a fresh session after a cancel — the next
            // send() must re-send config.
            configSent = false;
        }
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        WebSocket owned = connector.close();
        if (owned != null) ws = owned;
        if (ws == null) return;
        // Send {"close": true} and wait for the server's session_closed
        // reply BEFORE sending a WS close frame. Sending the WS close
        // immediately (the previous behaviour) raced with the server's
        // graceful-drain path and truncated audio: buffered tokens were
        // never flushed into the feeder, so onChunk never fired and the
        // test suite saw an empty audio stream.
        try {
            ObjectNode closeMsg = MAPPER.createObjectNode();
            closeMsg.put("close", true);
            ws.sendText(MAPPER.writeValueAsString(closeMsg), true).join();
        } catch (Exception ignored) {}
        boolean drained = false;
        try {
            drained = sessionClosedLatch.await(15, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        Diagnostics.Operation open = turn.get();
        if (!drained && open != null) {
            // The open turn never reached its final frame.
            open.stage(Diagnostics.STAGE_FINALIZING);
            failTurn(new ConnectionException(
                    "Streaming session did not acknowledge close within 15 s."), false, null);
        }
        try {
            ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
        } catch (Exception ignored) {}
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
            KugelAudioException wrapped =
                    new KugelAudioException("Failed to send message: " + e.getMessage(), e);
            failTurn(wrapped, false, null);
            throw wrapped;
        }
    }

    /**
     * Internal listener for the /ws/tts/stream endpoint.
     *
     * <p>The stream protocol sends: generation_started, audio frames, chunk_complete,
     * word_timestamps, and session_closed. All are dispatched to the appropriate callback.
     */
    final class SessionListener implements WebSocket.Listener {
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

                if (json.has("error")) {
                    // Let a pending updateSettings() reject before the generic
                    // onError sink fires (KUG-1166).
                    onSettingsFrame(json);
                    KugelAudioException frameError = TTSResource.mapWsError(json);
                    failTurn(frameError, true, null);
                    callbacks.onError(frameError);
                    webSocket.request(1);
                    return null;
                }

                if (json.path("settings_updated").asBoolean(false)) {
                    onSettingsFrame(json);
                }

                if (json.path("generation_started").asBoolean(false)) {
                    int chunkId = json.path("chunk_id").asInt(0);
                    String text = json.path("text").asText("");
                    callbacks.onGenerationStarted(chunkId, text);
                }

                if (json.has("audio") && !json.get("audio").isNull()) {
                    String audioBase64 = json.get("audio").asText();
                    String enc = json.path("enc").asText(AudioChunk.PCM_S16LE);
                    int idx = json.path("idx").asInt(0);
                    int sr = json.path("sr").asInt(24000);
                    int samples = json.path("samples").asInt(0);
                    callbacks.onChunk(AudioChunk.fromServerMessage(audioBase64, enc, idx, sr, samples));
                }

                if (json.path("chunk_complete").asBoolean(false)) {
                    int chunkId = json.path("chunk_id").asInt(0);
                    double audioSeconds = json.path("audio_seconds").asDouble(0);
                    double genMs = json.path("gen_ms").asDouble(0);
                    callbacks.onChunkComplete(chunkId, audioSeconds, genMs);
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

                if (json.path("interrupted").asBoolean(false)) {
                    callbacks.onInterrupted();
                }

                if (json.path("final").asBoolean(false)) {
                    // End-of-audio marker for the turn (KUG-1238): the turn succeeded.
                    endTurn(Diagnostics.Operation::success);
                }

                if (json.path("session_closed").asBoolean(false)) {
                    double totalAudioSeconds = json.path("total_audio_seconds").asDouble(0);
                    int totalTextChunks = json.path("total_text_chunks").asInt(0);
                    int totalAudioChunks = json.path("total_audio_chunks").asInt(0);
                    SessionUsage usage = SessionUsage.fromSessionClosed(json);
                    if (usage != null) lastUsage = usage;
                    callbacks.onSessionClosed(totalAudioSeconds, totalTextChunks, totalAudioChunks);
                }

            } catch (Exception e) {
                KugelAudioException wrapped =
                        new KugelAudioException("Error processing message: " + e.getMessage(), e);
                failTurn(wrapped, false, null);
                callbacks.onError(wrapped);
            }

            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            // Mirror Python/JS: only server-initiated error codes (4001/4003/
            // 4029/4500) surface as errors. Normal (1000), going-away (1001),
            // abnormal (1006), and any other WS-spec codes are end-of-stream.
            if (Errors.isErrorCloseCode(statusCode)) {
                KugelAudioException closeError = TTSResource.mapWsCloseCode(statusCode, reason);
                failTurn(closeError, true, statusCode);
                callbacks.onError(closeError);
            } else if (statusCode != WebSocket.NORMAL_CLOSURE && turn.get() != null) {
                // Not raised to the caller (mirrors Python/JS), but a turn that
                // was still open did not complete: diagnostics-only stream_interrupted.
                failTurn(TTSResource.mapWsCloseCode(statusCode, reason), false, statusCode);
            }
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            ConnectionException wrapped =
                    new ConnectionException("WebSocket error: " + error.getMessage(), error);
            failTurn(wrapped, false, null);
            callbacks.onError(wrapped);
        }
    }
}
