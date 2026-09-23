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

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.locks.ReentrantLock;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Resource for text-to-speech generation via WebSocket streaming.
 * Supports connection pooling for low-latency repeated requests.
 *
 * <p>Java's {@link WebSocket} API binds a {@link WebSocket.Listener} at creation
 * time and does not support swapping it. To enable connection reuse across
 * multiple requests, a single {@link DispatchListener} stays attached to the
 * pooled connection for its entire lifetime. For each request the active
 * delegate ({@link RequestHandler}) is swapped atomically so incoming messages
 * are routed to the correct caller.
 *
 * <pre>{@code
 * // One-shot generation (collects all chunks)
 * AudioResponse response = client.tts().generate(
 *     GenerateRequest.builder("Hello!").voiceId(123).build()
 * );
 * response.saveWav(Path.of("hello.wav"));
 *
 * // Streaming with callbacks
 * client.tts().stream(
 *     GenerateRequest.builder("Hello!").voiceId(123).build(),
 *     new StreamCallbacks() {
 *         public void onChunk(AudioChunk chunk) { // process }
 *     }
 * );
 * }</pre>
 */
public final class TTSResource {

    private static final Logger LOG = LoggerFactory.getLogger(TTSResource.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** Max payload size (bytes) for embedding in the WS URL query param. Beyond this, fall back to sendText. */
    private static final int MAX_INITIAL_MESSAGE_BYTES = 4096;

    private final KugelAudioOptions options;
    private final HttpClient httpClient;
    private final Diagnostics diagnostics;
    private final WsConnector connector = new WsConnector();

    private volatile WebSocket pooledConnection;
    private volatile String pooledUrl;
    private volatile DispatchListener pooledDispatch;
    private final ReentrantLock poolLock = new ReentrantLock();

    private final AtomicBoolean eagerConnectStarted = new AtomicBoolean(false);

    private final ScheduledExecutorService keepaliveScheduler;
    private volatile ScheduledFuture<?> keepaliveFuture;

    TTSResource(KugelAudioOptions options, HttpClient httpClient, Diagnostics diagnostics) {
        this.options = options;
        this.httpClient = httpClient;
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        this.keepaliveScheduler = options.getKeepalivePingInterval() != null
                ? Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "kugelaudio-keepalive");
                    t.setDaemon(true);
                    return t;
                })
                : null;
    }

    /**
     * Starts establishing the WebSocket connection in a background thread.
     * Called automatically by KugelAudio constructor when autoConnect is true.
     * Safe to call multiple times; only the first invocation starts the connect.
     */
    void startEagerConnect() {
        if (!eagerConnectStarted.compareAndSet(false, true)) {
            return;
        }
        CompletableFuture.runAsync(() -> {
            try {
                getOrCreatePooledNoPayload();
                LOG.info("WebSocket connection pre-established (auto-connect)");
            } catch (Exception e) {
                LOG.warn("Auto-connect failed (will retry on first request): {}", e.getMessage());
            }
        });
    }

    /**
     * Generates speech and returns the complete audio.
     * Internally uses streaming and collects all chunks.
     */
    public AudioResponse generate(GenerateRequest request) {
        CompletableFuture<AudioResponse> result = new CompletableFuture<>();

        stream(request, new StreamCallbacks() {
            @Override
            public void onChunk(AudioChunk chunk) {}

            @Override
            public void onComplete(AudioResponse response) {
                result.complete(response);
            }
        }, true, Diagnostics.OP_GENERATE);

        return result.getNow(AudioResponse.builder()
                .sampleRate(request.getSampleRate() != null ? request.getSampleRate() : 24000)
                .audio(new byte[0])
                .build());
    }

    /**
     * Streams speech with callbacks for each chunk. Uses connection pooling by default.
     */
    public void stream(GenerateRequest request, StreamCallbacks callbacks) {
        stream(request, callbacks, true);
    }

    /**
     * Streams speech with callbacks for each chunk.
     *
     * @param reuseConnection If true, reuses an existing WebSocket connection
     *                        (saves ~150-300ms per request by avoiding TLS + WS handshake).
     */
    public void stream(GenerateRequest request, StreamCallbacks callbacks, boolean reuseConnection) {
        stream(request, callbacks, reuseConnection, Diagnostics.OP_STREAM);
    }

    private void stream(
            GenerateRequest request,
            StreamCallbacks callbacks,
            boolean reuseConnection,
            String operationName) {
        ObjectNode payload = buildPayload(request);
        // Minted before connecting so a handshake failure still carries an
        // operation id; the handler reports at most one event for it.
        Diagnostics.Operation op = diagnostics
                .startOperation(operationName, Diagnostics.TRANSPORT_WEBSOCKET)
                .stage(Diagnostics.STAGE_HANDSHAKE);

        try {
            String payloadJson = MAPPER.writeValueAsString(payload);
            // Only embed payload in URL if it fits safely (nginx default header limit is 8KB)
            String initialMsg = payloadJson.length() <= MAX_INITIAL_MESSAGE_BYTES ? payloadJson : null;
            RequestHandler handler = new RequestHandler(callbacks, op);

            if (reuseConnection) {
                PooledWs pw = getOrCreatePooled(initialMsg);
                pw.dispatch.setDelegate(handler);
                op.stage(Diagnostics.STAGE_AWAITING_FIRST_AUDIO);
                if (!pw.usedInitialMessage) {
                    pw.ws.sendText(payloadJson, true).join();
                }
            } else {
                String wsUrl = WsUrlBuilder.build(options, "/ws/tts", initialMsg);
                StreamListener listener = new StreamListener(callbacks, op);
                WebSocket ws = connector.connect(
                        httpClient, URI.create(wsUrl), listener, options.getTimeout());
                op.stage(Diagnostics.STAGE_AWAITING_FIRST_AUDIO);
                if (initialMsg == null) {
                    ws.sendText(payloadJson, true).join();
                }
                handler = null;
                listener.awaitCompletion(options.getTimeout());
                op.success();
                return;
            }

            handler.awaitCompletion(options.getTimeout());
            op.success();

        } catch (KugelAudioException e) {
            op.fail(e);
            callbacks.onError(e);
            throw e;
        } catch (TimeoutException e) {
            KugelAudioException wrapped = new ConnectionException("Request timed out");
            op.fail(wrapped);
            callbacks.onError(wrapped);
            throw wrapped;
        } catch (Exception e) {
            KugelAudioException typed = Errors.classifyWsHandshake(e);
            KugelAudioException wrapped = typed != null
                    ? typed
                    : new ConnectionException("Streaming failed: " + e.getMessage(), e);
            op.fail(wrapped);
            callbacks.onError(wrapped);
            throw wrapped;
        }
    }

    /**
     * Pre-establishes a WebSocket connection for faster first request.
     * Call this at startup or before the first TTS request to eliminate
     * the ~150-300ms connection overhead from TTFA.
     */
    public void connect() {
        Diagnostics.Operation op = diagnostics
                .startOperation(Diagnostics.OP_STREAM, Diagnostics.TRANSPORT_WEBSOCKET)
                .stage(Diagnostics.STAGE_CONNECTING);
        try {
            getOrCreatePooledNoPayload();
            LOG.info("WebSocket connection pre-established");
            op.success();
        } catch (Exception e) {
            ConnectionException wrapped =
                    new ConnectionException("Failed to pre-connect: " + e.getMessage(), e);
            op.fail(wrapped);
            throw wrapped;
        }
    }

    /** Returns true if a pooled WebSocket connection is active. */
    public boolean isConnected() {
        WebSocket ws = pooledConnection;
        return ws != null && !ws.isInputClosed() && !ws.isOutputClosed();
    }

    /** Closes the pooled WebSocket connection and shuts down keepalive. */
    public void close() {
        connector.close();
        poolLock.lock();
        try { closePooledQuietly(); } finally { poolLock.unlock(); }
        if (keepaliveScheduler != null) {
            keepaliveScheduler.shutdown();
        }
    }

    // ── Connection pool management ──────────────────────────────────────

    private record PooledWs(WebSocket ws, DispatchListener dispatch, boolean usedInitialMessage) {}

    private PooledWs getOrCreatePooledNoPayload() throws Exception {
        return getOrCreatePooled(null);
    }

    /**
     * Gets or creates a pooled WebSocket. When creating a new connection and
     * {@code initialMsg} is non-null, the payload is embedded in the URL so the
     * server can process it immediately (zero-RTT fast path).
     */
    private PooledWs getOrCreatePooled(String initialMsg) throws Exception {
        long deadline = System.nanoTime() + options.getTimeout().toNanos();
        if (!poolLock.tryLock(options.getTimeout().toNanos(), TimeUnit.NANOSECONDS)) {
            throw new TimeoutException("Timed out waiting for the WebSocket connection lock");
        }
        try {
            String baseWsUrl = WsUrlBuilder.build(options, "/ws/tts");
            WebSocket ws = pooledConnection;
            if (ws != null && !ws.isInputClosed() && !ws.isOutputClosed() && baseWsUrl.equals(pooledUrl)) {
                return new PooledWs(ws, pooledDispatch, false);
            }
            closePooledQuietly();

            String connectUrl = initialMsg != null
                    ? WsUrlBuilder.build(options, "/ws/tts", initialMsg)
                    : baseWsUrl;
            DispatchListener dispatch = new DispatchListener();
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) throw new TimeoutException("Connection acquisition deadline expired");
            ws = connector.connect(httpClient, URI.create(connectUrl), dispatch, Duration.ofNanos(remaining));
            pooledConnection = ws;
            pooledUrl = baseWsUrl;
            pooledDispatch = dispatch;
            startKeepalive(ws);
            return new PooledWs(ws, dispatch, initialMsg != null);
        } finally { poolLock.unlock(); }
    }

    private void closePooledQuietly() {
        WebSocket ws = pooledConnection;
        if (ws != null) {
            try {
                ws.sendClose(WebSocket.NORMAL_CLOSURE, "close").join();
            } catch (Exception ignored) {}
            pooledConnection = null;
            pooledUrl = null;
            pooledDispatch = null;
        }
        stopKeepalive();
    }

    private void startKeepalive(WebSocket ws) {
        if (keepaliveScheduler == null) return;
        stopKeepalive();
        long intervalMs = options.getKeepalivePingInterval().toMillis();
        keepaliveFuture = keepaliveScheduler.scheduleAtFixedRate(() -> {
            WebSocket current = pooledConnection;
            if (current == null || current.isInputClosed() || current.isOutputClosed()) {
                stopKeepalive();
                return;
            }
            try {
                current.sendPing(ByteBuffer.allocate(0));
                LOG.debug("Keepalive ping sent on pooled WebSocket");
            } catch (Exception e) {
                LOG.debug("Keepalive ping failed (connection may be closed): {}", e.getMessage());
            }
        }, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
    }

    private void stopKeepalive() {
        ScheduledFuture<?> f = keepaliveFuture;
        if (f != null) {
            f.cancel(false);
            keepaliveFuture = null;
        }
    }

    private static volatile boolean languageWarningLogged = false;

    static void warnIfNoLanguage(String language, Boolean normalize) {
        boolean normEnabled = normalize == null || normalize;
        if (language == null && normEnabled && !languageWarningLogged) {
            languageWarningLogged = true;
            LOG.warn("No 'language' set with normalization enabled: the server normalizes in the " +
                    "voice's primary language (English if it has none). Set .language(\"de\") " +
                    "when the text is in another language.");
        }
    }

    static ObjectNode buildPayload(GenerateRequest request) {
        warnIfNoLanguage(request.getLanguage(), request.getNormalize());
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("text", request.getText());
        if (request.getModelId() != null) payload.put("model_id", request.getModelId());
        if (request.getVoiceId() != null) payload.put("voice_id", request.getVoiceId());
        if (request.getCfgScale() != null) payload.put("cfg_scale", request.getCfgScale());
        if (request.getTemperature() != null) payload.put("temperature", request.getTemperature());
        if (request.getMaxNewTokens() != null) payload.put("max_new_tokens", request.getMaxNewTokens());
        if (request.getSampleRate() != null) payload.put("sample_rate", request.getSampleRate());
        if (request.getOutputFormat() != null) payload.put("output_format", request.getOutputFormat());
        if (request.getNormalize() != null) payload.put("normalize", request.getNormalize());
        if (request.getLanguage() != null) payload.put("language", request.getLanguage());
        if (request.getWordTimestamps() != null && request.getWordTimestamps()) {
            payload.put("word_timestamps", true);
        }
        if (request.getSpeed() != null) payload.put("speed", request.getSpeed());
        if (request.getProjectId() != null) payload.put("project_id", request.getProjectId());
        // An empty list is meaningful (explicit opt-out) and must be sent;
        // only null (use the project default) is omitted.
        if (request.getDictionaryIds() != null) {
            payload.putPOJO("dictionary_ids", request.getDictionaryIds());
        }
        return payload;
    }

    static KugelAudioException mapWsError(JsonNode data) {
        return Errors.classifyWsFrame(data);
    }

    static KugelAudioException mapWsCloseCode(int code, String reason) {
        return Errors.classifyWsClose(code, reason);
    }

    /** A server error close code is a rejection; any other abnormal close is a dropped stream. */
    private static void failOnClose(Diagnostics.Operation op, int code, KugelAudioException error) {
        if (Errors.isErrorCloseCode(code)) op.rejected(error);
        else op.fail(error);
    }

    // ── Message processing (shared by both listener types) ──────────────

    static void processMessage(String msg, RequestHandler handler) {
        try {
            JsonNode json = MAPPER.readTree(msg);

            if (json.has("error")) {
                KugelAudioException ex = mapWsError(json);
                handler.op.rejected(ex);
                handler.callbacks.onError(ex);
                handler.completion.completeExceptionally(ex);
                return;
            }

            if (json.has("audio") && !json.get("audio").isNull()) {
                String audioBase64 = json.get("audio").asText();
                String enc = json.path("enc").asText(AudioChunk.PCM_S16LE);
                int idx = json.path("idx").asInt(handler.chunkCount);
                int sr = json.path("sr").asInt(24000);
                int samples = json.path("samples").asInt(0);

                AudioChunk chunk = AudioChunk.fromServerMessage(audioBase64, enc, idx, sr, samples);
                handler.allAudio.writeBytes(chunk.getAudio());
                handler.chunkCount++;
                handler.op.chunk(chunk.getAudio() == null ? 0 : chunk.getAudio().length);
                handler.responseBuilder.encoding(enc);
                handler.responseBuilder.sampleRate(sr);
                handler.callbacks.onChunk(chunk);
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
                handler.responseBuilder.addWordTimestamps(timestamps);
                handler.callbacks.onWordTimestamps(timestamps);
            }

            if (json.path("final").asBoolean(false)) {
                handler.responseBuilder
                        .totalSamples(json.path("total_samples").asInt(0))
                        .durationMs(json.path("dur_ms").asDouble(0))
                        .generationMs(json.path("gen_ms").asDouble(0))
                        .rtf(json.path("rtf").asDouble(0))
                        .usage(SessionUsage.fromSessionClosed(json))
                        .audio(handler.allAudio.toByteArray());
                handler.callbacks.onComplete(handler.responseBuilder.build());
                handler.completion.complete(null);
            }

        } catch (Exception e) {
            if (!handler.completion.isDone()) {
                handler.completion.completeExceptionally(e);
            }
        }
    }

    /**
     * Per-request state that accumulates audio and tracks completion.
     * Swapped into the {@link DispatchListener} for each new request.
     */
    static final class RequestHandler {
        final StreamCallbacks callbacks;
        final CompletableFuture<Void> completion = new CompletableFuture<>();
        final ByteArrayOutputStream allAudio = new ByteArrayOutputStream();
        final AudioResponse.Builder responseBuilder = AudioResponse.builder();
        /** Diagnostics handle for the enclosing operation; never null. */
        final Diagnostics.Operation op;
        volatile int chunkCount;

        RequestHandler(StreamCallbacks callbacks, Diagnostics.Operation op) {
            this.callbacks = callbacks;
            this.op = op;
        }

        void awaitCompletion(java.time.Duration timeout) throws Exception {
            try {
                completion.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.ExecutionException e) {
                // Unwrap the typed SDK exception set via completeExceptionally
                // (error frame / close code) — otherwise callers see every
                // server error degraded to ConnectionException("Streaming
                // failed: ...") by the generic catch in stream().
                if (e.getCause() instanceof KugelAudioException ke) throw ke;
                throw e;
            }
        }
    }

    /**
     * Long-lived listener attached to the pooled WebSocket. Delegates all
     * incoming messages to the currently active {@link RequestHandler}.
     * Between requests the delegate is null and incoming messages are discarded.
     */
    private static final class DispatchListener implements WebSocket.Listener {
        private volatile RequestHandler delegate;
        private final StringBuilder textBuffer = new StringBuilder();

        void setDelegate(RequestHandler handler) {
            this.delegate = handler;
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

            RequestHandler h = delegate;
            if (h != null) {
                processMessage(msg, h);
            }

            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            RequestHandler h = delegate;
            if (h != null && !h.completion.isDone()) {
                if (statusCode == WebSocket.NORMAL_CLOSURE || h.allAudio.size() > 0) {
                    h.responseBuilder.audio(h.allAudio.toByteArray());
                    h.callbacks.onComplete(h.responseBuilder.build());
                    h.completion.complete(null);
                } else {
                    KugelAudioException ex = mapWsCloseCode(statusCode, reason);
                    h.op.wsCloseCode(statusCode);
                    failOnClose(h.op, statusCode, ex);
                    h.completion.completeExceptionally(ex);
                }
            }
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            RequestHandler h = delegate;
            if (h != null) {
                KugelAudioException ex = new ConnectionException(
                        "WebSocket error: " + error.getMessage(), error);
                h.op.fail(ex);
                h.callbacks.onError(ex);
                if (!h.completion.isDone()) {
                    h.completion.completeExceptionally(ex);
                }
            }
        }
    }

    /**
     * Standalone listener used only for non-pooled (fresh connection) requests.
     * Owns its own {@link RequestHandler} and WebSocket lifecycle.
     */
    static final class StreamListener implements WebSocket.Listener {
        private final RequestHandler handler;
        private final StringBuilder textBuffer = new StringBuilder();

        StreamListener(StreamCallbacks callbacks, Diagnostics.Operation op) {
            this.handler = new RequestHandler(callbacks, op);
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
            processMessage(msg, handler);
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            if (!handler.completion.isDone()) {
                if (statusCode == WebSocket.NORMAL_CLOSURE || handler.allAudio.size() > 0) {
                    handler.responseBuilder.audio(handler.allAudio.toByteArray());
                    handler.callbacks.onComplete(handler.responseBuilder.build());
                    handler.completion.complete(null);
                } else {
                    KugelAudioException ex = mapWsCloseCode(statusCode, reason);
                    handler.op.wsCloseCode(statusCode);
                    failOnClose(handler.op, statusCode, ex);
                    handler.completion.completeExceptionally(ex);
                }
            }
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            KugelAudioException ex = new ConnectionException(
                    "WebSocket error: " + error.getMessage(), error);
            handler.op.fail(ex);
            handler.callbacks.onError(ex);
            if (!handler.completion.isDone()) {
                handler.completion.completeExceptionally(ex);
            }
        }

        void awaitCompletion(java.time.Duration timeout) throws Exception {
            handler.awaitCompletion(timeout);
        }
    }
}
