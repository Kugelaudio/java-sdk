package com.kugelaudio.sdk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Diagnostic to pinpoint TTFA overhead: breaks down connect vs send vs first-chunk,
 * and compares raw WebSocket reuse with the SDK's connection pooling.
 */
public class TTFADiagnostic {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static void main(String[] args) throws Exception {
        String apiKey = args.length > 0 ? args[0] : System.getenv("API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            System.err.println("Usage: TTFADiagnostic <api_key>");
            System.exit(1);
        }

        String baseUrl = "https://api.kugelaudio.com";
        String wsUrl = "wss://api.kugelaudio.com/ws/tts?api_key=" + apiKey;
        HttpClient httpClient = HttpClient.newHttpClient();

        System.out.println("=== TTFA Diagnostic ===\n");

        // ── Test 1: Break down fresh connection ──
        System.out.println("── 1. Fresh connection breakdown (3 runs) ──");
        for (int run = 0; run < 3; run++) {
            freshConnectionBreakdown(httpClient, wsUrl, "Hello, this is a test.", run + 1);
            Thread.sleep(1500);
        }

        // ── Test 2: Reuse raw WS (baseline) ──
        System.out.println("\n── 2. Reused raw WebSocket (3 requests on 1 connection) ──");
        reusedConnectionTest(httpClient, wsUrl);

        // ── Test 3: SDK with connection pooling ──
        System.out.println("\n── 3. SDK connection pooling (5 requests) ──");
        sdkPoolingTest(apiKey, baseUrl);

        // ── Test 4: SDK auto-connect (default, no user action) ──
        System.out.println("\n── 4. SDK auto-connect (default, no user action) ──");
        sdkAutoConnectTest(apiKey, baseUrl);

        System.out.println("\n=== Done ===");
    }

    private static void freshConnectionBreakdown(HttpClient httpClient, String wsUrl, String text, int run) throws Exception {
        long t0 = System.nanoTime();

        AtomicLong connectDone = new AtomicLong();
        AtomicLong firstChunk = new AtomicLong();
        CountDownLatch done = new CountDownLatch(1);

        WebSocket ws = httpClient.newWebSocketBuilder()
                .buildAsync(URI.create(wsUrl), new WebSocket.Listener() {
                    private final StringBuilder buf = new StringBuilder();

                    @Override
                    public void onOpen(WebSocket webSocket) {
                        connectDone.set(System.nanoTime());
                        webSocket.request(1);
                    }

                    @Override
                    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                        buf.append(data);
                        if (!last) { webSocket.request(1); return null; }
                        String msg = buf.toString();
                        buf.setLength(0);
                        try {
                            JsonNode json = MAPPER.readTree(msg);
                            if (json.has("audio") && !json.get("audio").isNull()) {
                                firstChunk.compareAndSet(0, System.nanoTime());
                            }
                            if (json.path("final").asBoolean(false)) done.countDown();
                        } catch (Exception ignored) {}
                        webSocket.request(1);
                        return null;
                    }

                    @Override
                    public CompletionStage<?> onClose(WebSocket webSocket, int code, String reason) {
                        done.countDown();
                        return null;
                    }

                    @Override
                    public void onError(WebSocket webSocket, Throwable error) {
                        System.err.println("  WS error: " + error.getMessage());
                        done.countDown();
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

        double connectMs = (afterConnect - t0) / 1_000_000.0;
        double sendMs = (afterSend - afterConnect) / 1_000_000.0;
        double serverTtfa = firstChunk.get() > 0 ? (firstChunk.get() - afterSend) / 1_000_000.0 : -1;
        double totalTtfa = firstChunk.get() > 0 ? (firstChunk.get() - t0) / 1_000_000.0 : -1;

        System.out.printf("  Run %d: Connect: %6.1f ms | Send: %4.1f ms | Server→1st: %5.1f ms | Total TTFA: %6.1f ms%n",
                run, connectMs, sendMs, serverTtfa, totalTtfa);

        ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
    }

    private static void reusedConnectionTest(HttpClient httpClient, String wsUrl) throws Exception {
        CompletableFuture<Void> ready = new CompletableFuture<>();
        AtomicLong firstChunk = new AtomicLong();
        CountDownLatch[] latches = { new CountDownLatch(1), new CountDownLatch(1), new CountDownLatch(1) };
        int[] currentRequest = {0};

        long t0 = System.nanoTime();
        WebSocket ws = httpClient.newWebSocketBuilder()
                .buildAsync(URI.create(wsUrl), new WebSocket.Listener() {
                    private final StringBuilder buf = new StringBuilder();

                    @Override public void onOpen(WebSocket webSocket) { ready.complete(null); webSocket.request(1); }

                    @Override
                    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                        buf.append(data);
                        if (!last) { webSocket.request(1); return null; }
                        String msg = buf.toString();
                        buf.setLength(0);
                        try {
                            JsonNode json = MAPPER.readTree(msg);
                            if (json.has("audio") && !json.get("audio").isNull()) firstChunk.compareAndSet(0, System.nanoTime());
                            if (json.path("final").asBoolean(false)) latches[currentRequest[0]].countDown();
                        } catch (Exception ignored) {}
                        webSocket.request(1);
                        return null;
                    }

                    @Override public CompletionStage<?> onClose(WebSocket webSocket, int code, String reason) { for (CountDownLatch l : latches) l.countDown(); return null; }
                })
                .get(10, TimeUnit.SECONDS);

        ready.get(10, TimeUnit.SECONDS);
        double connectMs = (System.nanoTime() - t0) / 1_000_000.0;
        System.out.printf("  Connect: %.1f ms%n", connectMs);

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
            double ttfa = firstChunk.get() > 0 ? (firstChunk.get() - sendStart) / 1_000_000.0 : -1;
            System.out.printf("  Request %d pure TTFA: %5.1f ms%n", i + 1, ttfa);
            Thread.sleep(500);
        }

        ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
    }

    private static void sdkPoolingTest(String apiKey, String baseUrl) throws Exception {
        KugelAudio client = new KugelAudio(
                KugelAudioOptions.builder(apiKey).apiUrl(baseUrl).build()
        );

        String[] texts = {
                "Hello, this is a test.",
                "The quick brown fox jumps.",
                "KugelAudio provides ultra-low latency.",
                "Short test.",
                "Another sentence to measure consistency."
        };

        for (int i = 0; i < texts.length; i++) {
            long t0 = System.nanoTime();
            AtomicLong firstChunkTime = new AtomicLong();

            client.tts().stream(
                    GenerateRequest.builder(texts[i]).modelId("kugel-1-turbo").language("en").build(),
                    new StreamCallbacks() {
                        @Override
                        public void onChunk(AudioChunk chunk) {
                            firstChunkTime.compareAndSet(0, System.nanoTime());
                        }
                    },
                    true
            );

            double ttfa = firstChunkTime.get() > 0 ? (firstChunkTime.get() - t0) / 1_000_000.0 : -1;
            String label = i == 0 ? "(incl. connect)" : "(pooled)       ";
            System.out.printf("  Request %d %s TTFA: %6.1f ms%n", i + 1, label, ttfa);
            Thread.sleep(500);
        }

        client.close();
    }

    private static void sdkAutoConnectTest(String apiKey, String baseUrl) throws Exception {
        long createStart = System.nanoTime();
        KugelAudio client = new KugelAudio(
                KugelAudioOptions.builder(apiKey).apiUrl(baseUrl).build()
        );
        double createMs = (System.nanoTime() - createStart) / 1_000_000.0;
        System.out.printf("  Client created in: %.1f ms (background connect started)%n", createMs);

        Thread.sleep(500);
        System.out.printf("  isConnected after 500ms: %s%n", client.isConnected());

        String[] texts = {
                "Hello, this is a test.",
                "The quick brown fox jumps.",
                "KugelAudio provides ultra-low latency.",
                "Short test.",
                "Another sentence to measure consistency."
        };

        for (int i = 0; i < texts.length; i++) {
            long ts = System.nanoTime();
            AtomicLong firstChunkTime = new AtomicLong();

            client.tts().stream(
                    GenerateRequest.builder(texts[i]).modelId("kugel-1-turbo").language("en").build(),
                    new StreamCallbacks() {
                        @Override
                        public void onChunk(AudioChunk chunk) {
                            firstChunkTime.compareAndSet(0, System.nanoTime());
                        }
                    },
                    true
            );

            double ttfa = firstChunkTime.get() > 0 ? (firstChunkTime.get() - ts) / 1_000_000.0 : -1;
            System.out.printf("  Request %d TTFA: %5.1f ms%n", i + 1, ttfa);
            Thread.sleep(500);
        }

        client.close();
    }
}
