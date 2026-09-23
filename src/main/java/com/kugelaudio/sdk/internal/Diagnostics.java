package com.kugelaudio.sdk.internal;

import com.kugelaudio.sdk.KugelAudioException;
import com.kugelaudio.sdk.KugelAudioOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Client-side error diagnostics: one privacy-bounded OTLP/HTTP JSON event per
 * failed SDK operation, delivered off the caller's path.
 *
 * <p>Callers do not instrument throw sites. A public operation mints an
 * {@link Operation} handle up front, feeds it progress (stage, chunks, retries)
 * and calls {@link Operation#fail(Throwable)} once on the way out. The handle
 * reports at most one event for its lifetime.
 *
 * <p>Batches are POSTed to {@code <effective API base URL>/v1/sdk-diagnostics}
 * with the SDK's own auth headers — no second credential and no second host.
 * If that route answers {@code 404} three times in a row the client is talking
 * to an older ingress, and the reporter switches itself off for good rather
 * than pestering it.
 *
 * <p>Everything here is best-effort and silent: a telemetry failure never
 * surfaces to the caller and is never logged above DEBUG. Delivery runs on a
 * daemon thread, so it can never delay JVM exit.
 *
 * <p>Normative wire and delivery contract:
 * {@code services/ingress/docs/sdk-diagnostics-contract.md} (v3).
 */
public final class Diagnostics implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(Diagnostics.class);

    // ── Event names (kugel.event) ───────────────────────────────────────
    public static final String EVENT_CONNECTION_FAILED = "connection_failed";
    public static final String EVENT_REQUEST_FAILED = "request_failed";
    public static final String EVENT_STREAM_INTERRUPTED = "stream_interrupted";
    public static final String EVENT_RETRY_EXHAUSTED = "retry_exhausted";
    public static final String EVENT_SDK_STATS = "sdk_stats";

    // ── Operations (kugel.operation) ────────────────────────────────────
    public static final String OP_GENERATE = "generate";
    public static final String OP_STREAM = "stream";
    public static final String OP_STREAM_SESSION = "stream_session";
    public static final String OP_MULTI_CONTEXT = "multi_context";
    public static final String OP_TRANSCRIBE = "transcribe";
    public static final String OP_VOICES = "voices";
    public static final String OP_DICTIONARIES = "dictionaries";
    public static final String OP_MODELS = "models";

    // ── Failure stages (kugel.failure_stage) ────────────────────────────
    public static final String STAGE_CONNECTING = "connecting";
    public static final String STAGE_HANDSHAKE = "handshake";
    public static final String STAGE_SENDING_REQUEST = "sending_request";
    public static final String STAGE_AWAITING_FIRST_AUDIO = "awaiting_first_audio";
    public static final String STAGE_RECEIVING_AUDIO = "receiving_audio";
    public static final String STAGE_FINALIZING = "finalizing";

    // ── Transports (kugel.transport) ────────────────────────────────────
    public static final String TRANSPORT_HTTP = "http";
    public static final String TRANSPORT_WEBSOCKET = "websocket";

    // ── Outcomes (kugel.outcome) ────────────────────────────────────────
    // "cancelled" is deliberately absent: a caller-initiated cancellation emits
    // no individual event at all, it only moves a counter reported in sdk_stats.
    public static final String OUTCOME_FAILED = "failed";

    /** Bounded queue capacity; the oldest event is dropped when full. */
    public static final int QUEUE_CAPACITY = 64;
    /** Flush as soon as this many events are queued. */
    public static final int FLUSH_BATCH_SIZE = 8;
    /**
     * At most this many records per POST. Ingress answers {@code 413} above
     * 64 records or 64 KiB, so a full queue is drained in several POSTs.
     */
    public static final int MAX_RECORDS_PER_POST = 8;
    /** Flush this long after the first event of a batch was queued. */
    public static final long FLUSH_INTERVAL_MS = 5_000L;
    /** Per-request timeout for a delivery attempt. */
    public static final long REQUEST_TIMEOUT_MS = 3_000L;
    /** At most one retry, so two attempts in total. */
    public static final int MAX_ATTEMPTS = 2;
    /** Upper bound on how long {@link #close()} may block. */
    public static final long CLOSE_DEADLINE_MS = 1_000L;
    /**
     * Consecutive {@code 404} responses after which the reporter disables
     * itself permanently — the ingress predates {@code /v1/sdk-diagnostics}.
     */
    public static final int NOT_FOUND_DISABLE_THRESHOLD = 3;

    /**
     * Delivery seam. Unit tests inject a fake recording
     * {@code (url, headers, payload)}; production uses {@link HttpSender}.
     *
     * <p>Returns the HTTP status code so the reporter can see a {@code 404}
     * from an older ingress. A transport-level failure throws instead; that is
     * retried, a status is not.
     */
    public interface Sender {
        int send(String url, Map<String, String> headers, byte[] body) throws Exception;
    }

    private final DiagnosticsConfig config;
    private final String sdkVersion;
    private final Sender sender;
    private final boolean autoFlush;
    /** Auth headers for the diagnostics POST — the SDK's own, never a second scheme. */
    private final Map<String, String> authHeaders;

    private final ArrayDeque<DiagnosticsEvent> queue = new ArrayDeque<>(QUEUE_CAPACITY);
    private final Object queueLock = new Object();
    private final AtomicInteger successCount = new AtomicInteger();
    private final AtomicInteger failureCount = new AtomicInteger();
    private final AtomicInteger cancelledCount = new AtomicInteger();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicInteger notFoundStreak = new AtomicInteger();
    private final AtomicBoolean deliveryDisabled = new AtomicBoolean();
    private final AtomicBoolean exitFlushRegistered = new AtomicBoolean();

    /**
     * Reporters that have queued a record and are not closed yet, for the
     * contract's "Exit flush". Weak, so an abandoned client can still be
     * collected; one JVM shutdown hook serves them all.
     */
    private static final Set<Diagnostics> EXIT_FLUSH =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));
    private static final AtomicBoolean EXIT_HOOK_INSTALLED = new AtomicBoolean();

    private volatile ScheduledThreadPoolExecutor scheduler;

    private Diagnostics(
            DiagnosticsConfig config,
            String sdkVersion,
            Sender sender,
            boolean autoFlush,
            Map<String, String> authHeaders) {
        this.config = config;
        this.sdkVersion = sdkVersion;
        this.sender = sender;
        this.autoFlush = autoFlush;
        this.authHeaders = authHeaders == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(authHeaders));
    }

    /** The reporter for a client, resolved from its options and the environment. */
    public static Diagnostics create(KugelAudioOptions options) {
        DiagnosticsConfig config =
                DiagnosticsConfig.resolve(options.getEffectiveApiUrl(), options.getTelemetry());
        return new Diagnostics(
                config,
                SdkMetadata.sdkVersion(),
                new HttpSender(),
                true,
                HttpHelper.authHeaders(options));
    }

    /** A reporter that records nothing. For tests; every production call site gets the client's reporter. */
    public static Diagnostics disabled() {
        return new Diagnostics(
                DiagnosticsConfig.disabled(),
                SdkMetadata.sdkVersion(),
                (url, headers, body) -> 204,
                false,
                Map.of());
    }

    /**
     * A reporter with an injected sender, for tests.
     *
     * @param autoFlush when false, nothing is scheduled and delivery only
     *                  happens via {@link #flushNow()} — keeps queue-bound tests
     *                  deterministic.
     */
    public static Diagnostics withSender(
            DiagnosticsConfig config, String sdkVersion, Sender sender, boolean autoFlush) {
        return withSender(config, sdkVersion, sender, autoFlush, Map.of());
    }

    /** As above, with the auth headers the diagnostics POST must carry. */
    public static Diagnostics withSender(
            DiagnosticsConfig config,
            String sdkVersion,
            Sender sender,
            boolean autoFlush,
            Map<String, String> authHeaders) {
        return new Diagnostics(config, sdkVersion, sender, autoFlush, authHeaders);
    }

    public DiagnosticsConfig getConfig() {
        return config;
    }

    public boolean isEnabled() {
        return config.isEnabled() && !deliveryDisabled.get();
    }

    /**
     * True once {@link #NOT_FOUND_DISABLE_THRESHOLD} consecutive {@code 404}s
     * have switched the reporter off for the rest of this client's life.
     */
    public boolean isDisabledByNotFound() {
        return deliveryDisabled.get();
    }

    /** Mints an operation handle. Cheap and allocation-light even when disabled. */
    public Operation startOperation(String operation, String transport) {
        return new Operation(this, operation, transport);
    }

    /** Number of events currently queued. Test helper. */
    public int queuedCount() {
        synchronized (queueLock) {
            return queue.size();
        }
    }

    public int successCount() {
        return successCount.get();
    }

    public int failureCount() {
        return failureCount.get();
    }

    /**
     * Caller-initiated cancellations. Reported only in aggregate, as
     * {@code kugel.cancelled_count} on the {@code sdk_stats} event.
     */
    public int cancelledCount() {
        return cancelledCount.get();
    }

    /**
     * Queues an event. Never throws. Drops the oldest event when the queue is
     * full, so a burst of failures cannot grow memory without bound.
     */
    public void record(DiagnosticsEvent event) {
        if (event == null || !isEnabled() || closed.get()) return;
        int size;
        try {
            synchronized (queueLock) {
                if (queue.size() >= QUEUE_CAPACITY) {
                    queue.pollFirst();
                }
                queue.addLast(event);
                size = queue.size();
            }
            registerExitFlush();
            if (size >= FLUSH_BATCH_SIZE) {
                scheduleFlush(0L);
            } else if (size == 1) {
                scheduleFlush(FLUSH_INTERVAL_MS);
            }
        } catch (Throwable t) {
            LOG.debug("diagnostics record failed: {}", t.toString());
        }
    }

    /**
     * Drains the queue on the calling thread, at most
     * {@link #MAX_RECORDS_PER_POST} records per POST. Production code only
     * ever calls this from the background worker; it is public so tests can
     * force delivery without waiting on a timer.
     */
    public void flushNow() {
        while (true) {
            List<DiagnosticsEvent> batch = new ArrayList<>(MAX_RECORDS_PER_POST);
            synchronized (queueLock) {
                // No usable target, or switched off by repeated 404s: drop it all.
                if (deliveryDisabled.get() || config.getDiagnosticsUrl().isEmpty()) {
                    queue.clear();
                    return;
                }
                while (batch.size() < MAX_RECORDS_PER_POST && !queue.isEmpty()) {
                    batch.add(queue.pollFirst());
                }
            }
            if (batch.isEmpty()) return;
            try {
                deliver(OtlpEncoder.encode(sdkVersion, batch));
            } catch (Throwable t) {
                LOG.debug("diagnostics flush failed: {}", t.toString());
            }
        }
    }

    /**
     * Queues {@code sdk_stats}, hands the final flush to the background worker
     * and waits for it at most {@link #CLOSE_DEADLINE_MS} in total. Never does
     * network I/O on the caller's thread. A flush still running at the
     * deadline keeps going on its daemon thread; it cannot delay JVM exit.
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        EXIT_FLUSH.remove(this);
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(CLOSE_DEADLINE_MS);
        try {
            emitStats();
            ScheduledThreadPoolExecutor worker;
            synchronized (this) {
                if (scheduler == null && queuedCount() > 0) scheduler = newWorker();
                worker = scheduler;
            }
            if (worker == null) return;
            // Pending timers only flush what the final flush below already takes.
            worker.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
            Future<?> finalFlush = worker.submit(this::flushNow);
            worker.shutdown();
            try {
                finalFlush.get(Math.max(0L, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception ignored) {
                // Deadline exceeded: the daemon worker finishes or drops it.
            }
        } catch (Throwable t) {
            LOG.debug("diagnostics close failed: {}", t.toString());
        }
    }

    // ── Exit flush ──────────────────────────────────────────────────────

    /** Joins the exit-flush registry once; installs the single shutdown hook on first use. */
    private void registerExitFlush() {
        // autoFlush=false (test) reporters deliver only via flushNow().
        if (!autoFlush || !exitFlushRegistered.compareAndSet(false, true)) return;
        EXIT_FLUSH.add(this);
        if (EXIT_HOOK_INSTALLED.compareAndSet(false, true)) {
            try {
                Thread hook = new Thread(Diagnostics::runExitFlush, "kugelaudio-diagnostics-exit");
                // Never pin the registering thread's (e.g. a webapp's) classloader.
                hook.setContextClassLoader(null);
                Runtime.getRuntime().addShutdownHook(hook);
            } catch (Throwable t) {
                // Already shutting down, or hooks are forbidden: nothing to flush into.
                LOG.debug("diagnostics exit hook unavailable: {}", t.toString());
            }
        }
    }

    /**
     * The shutdown hook: for every reporter that still has records queued and
     * was never closed, hand one flush to its daemon worker, then wait at most
     * {@link #CLOSE_DEADLINE_MS} in total. Nothing queued means no wait at all.
     */
    private static void runExitFlush() {
        try {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(CLOSE_DEADLINE_MS);
            List<Diagnostics> reporters;
            synchronized (EXIT_FLUSH) {
                reporters = new ArrayList<>(EXIT_FLUSH);
            }
            List<Future<?>> pending = new ArrayList<>();
            for (Diagnostics reporter : reporters) {
                Future<?> flush = reporter.submitExitFlush();
                if (flush != null) pending.add(flush);
            }
            for (Future<?> flush : pending) {
                try {
                    flush.get(Math.max(0L, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                } catch (Exception ignored) {
                    // Deadline passed: the JVM exits and the batch is dropped.
                }
            }
        } catch (Throwable t) {
            LOG.debug("diagnostics exit flush failed: {}", t.toString());
        }
    }

    /** One flush on the worker, or {@code null} if closed, disabled or empty. */
    private Future<?> submitExitFlush() {
        if (closed.get() || !isEnabled() || queuedCount() == 0) return null;
        try {
            ScheduledThreadPoolExecutor worker = ensureScheduler();
            return worker == null ? null : worker.submit(this::flushNow);
        } catch (Throwable t) {
            // Closed concurrently (worker already shut down): nothing left to do.
            return null;
        }
    }

    private void emitStats() {
        int successes = successCount.get();
        int failures = failureCount.get();
        int cancellations = cancelledCount.get();
        if (!isEnabled() || (successes == 0 && failures == 0 && cancellations == 0)) return;
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("kugel.event", EVENT_SDK_STATS);
        attrs.put("kugel.event_id", newId());
        attrs.put("kugel.operation_id", newId());
        attrs.put("kugel.sdk.name", SdkMetadata.SDK_NAME);
        attrs.put("kugel.sdk.version", sdkVersion);
        attrs.put("kugel.runtime", runtime());
        attrs.put("kugel.integration", "none");
        attrs.put("kugel.endpoint_kind", config.getEndpointKind());
        attrs.put("kugel.success_count", successes);
        attrs.put("kugel.failure_count", failures);
        attrs.put("kugel.cancelled_count", cancellations);
        // Queue directly: record() short-circuits once closed is set.
        synchronized (queueLock) {
            if (queue.size() >= QUEUE_CAPACITY) queue.pollFirst();
            queue.addLast(new DiagnosticsEvent(EVENT_SDK_STATS, attrs));
        }
    }

    private void deliver(byte[] body) {
        Map<String, String> headers = new LinkedHashMap<>(authHeaders);
        headers.keySet().removeIf("Content-Type"::equalsIgnoreCase);
        // Set last, so no caller header can override it.
        headers.put("Content-Type", "application/json");
        Map<String, String> sent = Collections.unmodifiableMap(headers);
        String url = config.getDiagnosticsUrl();
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                int status = sender.send(url, sent, body);
                if (status >= 200 && status < 300) {
                    notFoundStreak.set(0);
                    return;
                }
                if (status == 404) {
                    // A definitive answer, not a blip: do not retry this batch.
                    if (notFoundStreak.incrementAndGet() >= NOT_FOUND_DISABLE_THRESHOLD) {
                        deliveryDisabled.set(true);
                        LOG.debug("diagnostics disabled after {} consecutive 404s", notFoundStreak.get());
                    }
                    return;
                }
                // Any other response breaks the 404 streak.
                notFoundStreak.set(0);
                if (status == 413) {
                    // Too large is deterministic: resending the same bytes cannot help.
                    LOG.debug("diagnostics batch rejected as too large (413)");
                    return;
                }
                LOG.debug("diagnostics delivery attempt {} returned {}", attempt, status);
            } catch (Throwable t) {
                // A transport failure is not a response: it neither counts
                // toward nor resets the 404 streak.
                LOG.debug("diagnostics delivery attempt {} failed: {}", attempt, t.toString());
            }
        }
    }

    private void scheduleFlush(long delayMs) {
        if (!autoFlush || closed.get() || deliveryDisabled.get()) return;
        ScheduledThreadPoolExecutor active = ensureScheduler();
        if (active == null) return;
        try {
            active.schedule(this::flushNow, delayMs, TimeUnit.MILLISECONDS);
        } catch (Throwable t) {
            LOG.debug("diagnostics schedule failed: {}", t.toString());
        }
    }

    private ScheduledThreadPoolExecutor ensureScheduler() {
        ScheduledThreadPoolExecutor active = scheduler;
        if (active != null) return active;
        synchronized (this) {
            if (scheduler == null && !closed.get()) scheduler = newWorker();
            return scheduler;
        }
    }

    /** One daemon thread: delivery can never keep the JVM alive. */
    private static ScheduledThreadPoolExecutor newWorker() {
        return new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, "kugelaudio-diagnostics");
            thread.setDaemon(true);
            thread.setContextClassLoader(null);
            return thread;
        });
    }

    static String newId() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        return String.format(
                Locale.ROOT, "%016x%016x", random.nextLong(), random.nextLong());
    }

    /** {@code java/<feature>.<interim>.<update>}, e.g. {@code java/17.0.2}; no vendor or build suffix. */
    public static String runtime() {
        Runtime.Version version = Runtime.version();
        return "java/" + version.feature() + "." + version.interim() + "." + version.update();
    }

    // ── Operation handle ────────────────────────────────────────────────

    /**
     * Per-operation state. Minted before connecting so {@code kugel.operation_id}
     * exists even for a failure that never reached the server. Terminal exactly
     * once: the first of {@link #fail}, {@link #rejected}, {@link #cancelled} or
     * {@link #success} wins. Only a failure produces an event.
     */
    public static final class Operation {

        private final Diagnostics diagnostics;
        private final String operationId = newId();
        private final String operation;
        private final String transport;
        private final long startNanos = System.nanoTime();

        private final AtomicInteger chunks = new AtomicInteger();
        private final AtomicLong bytes = new AtomicLong();
        private final AtomicInteger retries = new AtomicInteger();
        private final AtomicBoolean terminal = new AtomicBoolean();

        private volatile String stage;
        private volatile String serverRequestId;
        private volatile Integer httpStatus;
        private volatile Integer wsCloseCode;

        private Operation(Diagnostics diagnostics, String operation, String transport) {
            this.diagnostics = diagnostics;
            this.operation = operation;
            this.transport = transport;
        }

        public String getOperationId() {
            return operationId;
        }

        /** Records where the operation currently is; becomes {@code kugel.failure_stage}. */
        public Operation stage(String stage) {
            this.stage = stage;
            return this;
        }

        /** One audio chunk of {@code byteCount} bytes arrived. */
        public void chunk(int byteCount) {
            chunks.incrementAndGet();
            if (byteCount > 0) bytes.addAndGet(byteCount);
            stage = STAGE_RECEIVING_AUDIO;
        }

        /** A transport-level retry was attempted. */
        public void retry() {
            retries.incrementAndGet();
        }

        public Operation serverRequestId(String requestId) {
            if (requestId != null && !requestId.isBlank()) this.serverRequestId = requestId;
            return this;
        }

        public Operation httpStatus(int status) {
            if (status > 0) this.httpStatus = status;
            return this;
        }

        public Operation wsCloseCode(int code) {
            this.wsCloseCode = code;
            return this;
        }

        /** Terminal: the operation completed. Counts toward {@code sdk_stats}. */
        public void success() {
            if (!terminal.compareAndSet(false, true)) return;
            diagnostics.successCount.incrementAndGet();
        }

        /**
         * Terminal: the caller cancelled. Counts toward
         * {@code kugel.cancelled_count} on {@code sdk_stats} and emits NO
         * individual event.
         *
         * <p>Cancellation is not a fault. Emitting a record per cancellation
         * would make a barge-in-heavy voice agent ship one ERROR-severity OTLP
         * log line per interruption.
         */
        public void cancelled() {
            if (!terminal.compareAndSet(false, true)) return;
            diagnostics.cancelledCount.incrementAndGet();
        }

        /**
         * Terminal: a transport failure (no response, or the established
         * stream dropped). Reports exactly one event, classified by stage.
         */
        public void fail(Throwable error) {
            finish(error, false);
        }

        /**
         * Terminal: the server answered with an error — an error response, a
         * WebSocket error frame or an error close code. Reports exactly one
         * {@code request_failed} (or {@code retry_exhausted}) event.
         */
        public void rejected(Throwable error) {
            finish(error, true);
        }

        private void finish(Throwable error, boolean serverRejected) {
            if (!terminal.compareAndSet(false, true)) return;
            diagnostics.failureCount.incrementAndGet();
            if (!diagnostics.isEnabled()) return;
            try {
                String event = eventName(serverRejected);
                diagnostics.record(new DiagnosticsEvent(event, attributes(event, error)));
            } catch (Throwable t) {
                LOG.debug("diagnostics report failed: {}", t.toString());
            }
        }

        /** Contract "Event classification"; never derived from message text. */
        String eventName(boolean serverRejected) {
            if (retries.get() > 0) return EVENT_RETRY_EXHAUSTED;
            if (serverRejected) return EVENT_REQUEST_FAILED;
            String current = stage;
            if (current == null || STAGE_CONNECTING.equals(current) || STAGE_HANDSHAKE.equals(current)) {
                return EVENT_CONNECTION_FAILED;
            }
            if (STAGE_AWAITING_FIRST_AUDIO.equals(current) || STAGE_RECEIVING_AUDIO.equals(current)) {
                return EVENT_STREAM_INTERRUPTED;
            }
            return EVENT_REQUEST_FAILED;
        }

        private Map<String, Object> attributes(String event, Throwable error) {
            Map<String, Object> attrs = new LinkedHashMap<>();
            attrs.put("kugel.event", event);
            attrs.put("kugel.event_id", newId());
            attrs.put("kugel.operation_id", operationId);
            if (operation != null) attrs.put("kugel.operation", operation);
            attrs.put("kugel.sdk.name", SdkMetadata.SDK_NAME);
            attrs.put("kugel.sdk.version", diagnostics.sdkVersion);
            attrs.put("kugel.runtime", runtime());
            attrs.put("kugel.integration", "none");
            if (transport != null) attrs.put("kugel.transport", transport);
            if (stage != null) attrs.put("kugel.failure_stage", stage);

            if (error != null) {
                attrs.put("kugel.error_type", error.getClass().getSimpleName());
            }
            String requestId = serverRequestId;
            Integer status = httpStatus;
            if (error instanceof KugelAudioException typed) {
                if (typed.getErrorCode() != null) {
                    attrs.put("kugel.error_code", typed.getErrorCode());
                }
                if (status == null && typed.getStatusCode() > 0) {
                    status = typed.getStatusCode();
                }
                if (requestId == null) {
                    requestId = typed.getRequestId();
                }
            }
            if (status != null) attrs.put("kugel.http_status", status);
            if (wsCloseCode != null) attrs.put("kugel.ws_close_code", wsCloseCode);
            if (requestId != null && !requestId.isBlank()) {
                attrs.put("kugel.server_request_id", requestId);
            }

            attrs.put("kugel.elapsed_ms", (System.nanoTime() - startNanos) / 1_000_000L);
            attrs.put("kugel.audio_chunks", chunks.get());
            attrs.put("kugel.audio_bytes", bytes.get());
            attrs.put("kugel.retry_count", retries.get());
            attrs.put("kugel.outcome", OUTCOME_FAILED);
            attrs.put("kugel.endpoint_kind", diagnostics.config.getEndpointKind());
            return attrs;
        }
    }

    // ── Default transport ───────────────────────────────────────────────

    /** OTLP/HTTP JSON over {@link HttpClient}. No extra dependency. */
    static final class HttpSender implements Sender {

        private volatile HttpClient client;

        @Override
        public int send(String url, Map<String, String> headers, byte[] body) throws Exception {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofMillis(REQUEST_TIMEOUT_MS))
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body));
            headers.forEach(builder::header);
            HttpResponse<Void> response =
                    client().send(builder.build(), HttpResponse.BodyHandlers.discarding());
            // Non-2xx is a silent drop, decided by the caller: return the status.
            return response.statusCode();
        }

        private HttpClient client() {
            HttpClient existing = client;
            if (existing != null) return existing;
            synchronized (this) {
                if (client == null) {
                    client = HttpClient.newBuilder()
                            // HTTP/1.1: an h2c upgrade offer on plain http:// makes
                            // uvicorn drop the batch body (see KugelAudio's client).
                            .version(HttpClient.Version.HTTP_1_1)
                            .connectTimeout(Duration.ofMillis(REQUEST_TIMEOUT_MS))
                            .executor(daemonExecutor())
                            .build();
                }
                return client;
            }
        }

        private static ExecutorService daemonExecutor() {
            return Executors.newCachedThreadPool(runnable -> {
                Thread thread = new Thread(runnable, "kugelaudio-diagnostics-http");
                thread.setDaemon(true);
                thread.setContextClassLoader(null);
                return thread;
            });
        }
    }
}
