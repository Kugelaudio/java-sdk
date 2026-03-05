package com.kugelaudio.bench;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.kugelaudio.sdk.*;

import java.util.Locale;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Diagnostic TTFA breakdown: connect vs send vs server-to-first-chunk.
 * Mirrors TTFADiagnostic from the SDK source tree, using the published Maven Central artifact.
 *
 * Run:
 *   mvn compile exec:java -Dexec.mainClass=com.kugelaudio.bench.TTFADiagnosticBench \
 *       -Dexec.args="<api_key>"
 */
public class TTFADiagnosticBench {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String BASE_URL = "https://api.kugelaudio.com";

    public static void main(String[] args) throws Exception {
        String apiKey = args.length > 0 ? args[0] : System.getenv("API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            System.err.println("Usage: TTFADiagnosticBench <api_key> [api_url]   (or set API_KEY / API_URL env)");
            System.exit(1);
        }
        String apiUrl = args.length > 1 ? args[1] : System.getenv("API_URL");
        if (apiUrl == null || apiUrl.isBlank()) apiUrl = BASE_URL;
        // Derive WS URL from API URL (http→ws, https→wss)
        String wsUrl = apiUrl.replaceFirst("^https://", "wss://").replaceFirst("^http://", "ws://") + "/ws/tts?api_key=" + apiKey;
        HttpClient httpClient = HttpClient.newHttpClient();

        Locale.setDefault(Locale.US);
        System.out.println("=== KugelAudio TTFA Diagnostic ===");
        System.out.println("SDK: com.kugelaudio:kugelaudio:1.0.1 (Maven Central)");
        System.out.println();

        // ── 1. Fresh connection breakdown ──────────────────────────────────
        System.out.println("── 1. Fresh connection breakdown (3 runs) ──");
        System.out.println("  (Connect ms includes TLS handshake; Send ms is time to write JSON; Server→1st is pure server latency)");
        for (int run = 0; run < 3; run++) {
            freshConnectionBreakdown(httpClient, wsUrl, "Hello, this is a test.", run + 1);
            Thread.sleep(1500);
        }

        // ── 2. Reused raw WebSocket (baseline) ────────────────────────────
        System.out.println("\n── 2. Reused raw WebSocket (3 requests on 1 connection) ──");
        reusedConnectionTest(httpClient, wsUrl);

        // ── 3. SDK connection pooling ──────────────────────────────────────
        System.out.println("\n── 3. SDK connection pooling (5 requests) ──");
        sdkPoolingTest(apiKey);

        // ── 4. SDK auto-connect (background warm-up) ──────────────────────
        System.out.println("\n── 4. SDK auto-connect (500 ms warm-up then 5 requests) ──");
        sdkAutoConnectTest(apiKey);

        System.out.println("\n=== Done ===");
    }

    private static void freshConnectionBreakdown(HttpClient http, String wsUrl, String text, int run) throws Exception {
        long t0 = System.nanoTime();
        AtomicLong connectDone = new AtomicLong();
        AtomicLong firstChunk  = new AtomicLong();
        CountDownLatch done = new CountDownLatch(1);

        WebSocket ws = http.newWebSocketBuilder()
                .buildAsync(URI.create(wsUrl), new WebSocket.Listener() {
                    private final StringBuilder buf = new StringBuilder();

                    @Override public void onOpen(WebSocket ws) {
                        connectDone.set(System.nanoTime());
                        ws.request(1);
                    }

                    @Override public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
                        buf.append(data);
                        if (!last) { ws.request(1); return null; }
                        String msg = buf.toString(); buf.setLength(0);
                        try {
                            JsonNode json = MAPPER.readTree(msg);
                            if (json.has("audio") && !json.get("audio").isNull())
                                firstChunk.compareAndSet(0, System.nanoTime());
                            if (json.path("final").asBoolean(false)) done.countDown();
                        } catch (Exception ignored) {}
                        ws.request(1);
                        return null;
                    }

                    @Override public CompletionStage<?> onClose(WebSocket ws, int code, String reason) {
                        done.countDown(); return null;
                    }
                    @Override public void onError(WebSocket ws, Throwable e) {
                        System.err.println("  WS error: " + e.getMessage()); done.countDown();
                    }
                })
                .get(10, TimeUnit.SECONDS);

        long afterConnect = connectDone.get();
        if (afterConnect == 0) afterConnect = System.nanoTime();

        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("text", text);
        payload.put("model_id", "kugel-1-turbo");
        payload.put("language", "en");
        ws.sendText(MAPPER.writeValueAsString(payload), true).join();
        long afterSend = System.nanoTime();

        done.await(30, TimeUnit.SECONDS);

        double connectMs   = (afterConnect - t0) / 1e6;
        double sendMs      = (afterSend - afterConnect) / 1e6;
        double serverTtfa  = firstChunk.get() > 0 ? (firstChunk.get() - afterSend) / 1e6 : -1;
        double totalTtfa   = firstChunk.get() > 0 ? (firstChunk.get() - t0) / 1e6 : -1;

        System.out.printf("  Run %d  Connect: %6.1f ms | Send: %4.1f ms | Server→1st: %5.1f ms | Total TTFA: %6.1f ms%n",
                run, connectMs, sendMs, serverTtfa, totalTtfa);

        ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
    }

    private static void reusedConnectionTest(HttpClient http, String wsUrl) throws Exception {
        CompletableFuture<Void> ready = new CompletableFuture<>();
        AtomicLong firstChunk = new AtomicLong();
        CountDownLatch[] latches = { new CountDownLatch(1), new CountDownLatch(1), new CountDownLatch(1) };
        int[] currentRequest = {0};

        long t0 = System.nanoTime();
        WebSocket ws = http.newWebSocketBuilder()
                .buildAsync(URI.create(wsUrl), new WebSocket.Listener() {
                    private final StringBuilder buf = new StringBuilder();
                    @Override public void onOpen(WebSocket ws) { ready.complete(null); ws.request(1); }
                    @Override public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
                        buf.append(data);
                        if (!last) { ws.request(1); return null; }
                        String msg = buf.toString(); buf.setLength(0);
                        try {
                            JsonNode json = MAPPER.readTree(msg);
                            if (json.has("audio") && !json.get("audio").isNull())
                                firstChunk.compareAndSet(0, System.nanoTime());
                            if (json.path("final").asBoolean(false)) latches[currentRequest[0]].countDown();
                        } catch (Exception ignored) {}
                        ws.request(1);
                        return null;
                    }
                    @Override public CompletionStage<?> onClose(WebSocket ws, int code, String reason) {
                        for (CountDownLatch l : latches) l.countDown(); return null;
                    }
                })
                .get(10, TimeUnit.SECONDS);

        ready.get(10, TimeUnit.SECONDS);
        System.out.printf("  Initial connect: %.1f ms%n", (System.nanoTime() - t0) / 1e6);

        String[] texts = { "Hello, this is a test.", "The quick brown fox.", "KugelAudio ultra-low latency." };
        for (int i = 0; i < 3; i++) {
            currentRequest[0] = i;
            firstChunk.set(0);
            ObjectNode payload = MAPPER.createObjectNode();
            payload.put("text", texts[i]);
            payload.put("model_id", "kugel-1-turbo");
            payload.put("language", "en");
            long sendStart = System.nanoTime();
            ws.sendText(MAPPER.writeValueAsString(payload), true).join();
            latches[i].await(30, TimeUnit.SECONDS);
            double ttfa = firstChunk.get() > 0 ? (firstChunk.get() - sendStart) / 1e6 : -1;
            System.out.printf("  Request %d  pure server TTFA: %5.1f ms%n", i + 1, ttfa);
            Thread.sleep(500);
        }
        ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
    }

    private static void sdkPoolingTest(String apiKey) throws Exception {
        KugelAudio client = new KugelAudio(KugelAudioOptions.builder(apiKey).apiUrl(BASE_URL).build());
        String[] texts = {
                "Hello, this is a test.",
                "The quick brown fox jumps.",
                "KugelAudio provides ultra-low latency.",
                "Short test.",
                "Another sentence to measure consistency."
        };
        for (int i = 0; i < texts.length; i++) {
            long t0 = System.nanoTime();
            AtomicLong first = new AtomicLong();
            client.tts().stream(
                    GenerateRequest.builder(texts[i]).modelId("kugel-1-turbo").language("en").build(),
                    new StreamCallbacks() {
                        @Override public void onChunk(AudioChunk chunk) { first.compareAndSet(0, System.nanoTime()); }
                    },
                    true
            );
            double ttfa = first.get() > 0 ? (first.get() - t0) / 1e6 : -1;
            String label = i == 0 ? "(incl. connect)" : "(pooled)       ";
            System.out.printf("  Request %d %s  TTFA: %6.1f ms%n", i + 1, label, ttfa);
            Thread.sleep(500);
        }
        client.close();
    }

    private static void sdkAutoConnectTest(String apiKey) throws Exception {
        long createStart = System.nanoTime();
        KugelAudio client = new KugelAudio(KugelAudioOptions.builder(apiKey).apiUrl(BASE_URL).build());
        double createMs = (System.nanoTime() - createStart) / 1e6;
        System.out.printf("  Client created in: %.1f ms (background connect started)%n", createMs);
        Thread.sleep(500);
        System.out.printf("  isConnected after 500 ms: %s%n", client.isConnected());

        String[] texts = {
                "Hello, this is a test.",
                "The quick brown fox jumps.",
                "KugelAudio provides ultra-low latency.",
                "Short test.",
                "Another sentence to measure consistency."
        };
        for (int i = 0; i < texts.length; i++) {
            long ts = System.nanoTime();
            AtomicLong first = new AtomicLong();
            client.tts().stream(
                    GenerateRequest.builder(texts[i]).modelId("kugel-1-turbo").language("en").build(),
                    new StreamCallbacks() {
                        @Override public void onChunk(AudioChunk chunk) { first.compareAndSet(0, System.nanoTime()); }
                    },
                    true
            );
            double ttfa = first.get() > 0 ? (first.get() - ts) / 1e6 : -1;
            System.out.printf("  Request %d  TTFA: %5.1f ms%n", i + 1, ttfa);
            Thread.sleep(500);
        }
        client.close();
    }
}
