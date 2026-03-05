package com.kugelaudio.sdk;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Live TTFA (Time To First Audio) test against api.kugelaudio.com.
 * Run with: java ... TTFALiveTest <api_key>
 */
public class TTFALiveTest {

    private static final String API_URL = "https://api.kugelaudio.com";
    private static final String SHORT = "Hello, this is a quick test.";
    private static final String MEDIUM = "The quick brown fox jumps over the lazy dog near the riverbank on a warm summer afternoon.";
    private static final String LONG = "In a world where technology and nature intertwine, we find ourselves at the crossroads of innovation and tradition, seeking balance in an ever-changing landscape.";

    public static void main(String[] args) throws Exception {
        String apiKey = args.length > 0 ? args[0] : System.getenv("API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            System.err.println("Usage: TTFALiveTest <api_key>  (or set API_KEY env)");
            System.exit(1);
        }

        KugelAudioOptions options = KugelAudioOptions.builder(apiKey)
                .apiUrl(API_URL)
                .build();

        System.out.println("=== KugelAudio Java SDK — TTFA Live Test ===");
        System.out.println("Server: " + API_URL);
        System.out.println();

        try (KugelAudio client = new KugelAudio(options)) {

            // ── 1. Verify connectivity ──
            System.out.println("── Verifying connectivity ──");
            List<Model> models = client.models().list();
            for (Model m : models) {
                System.out.println("  Model: " + m.getId() + " — " + m.getName() + " (" + m.getParameters() + ")");
            }
            System.out.println();

            // ── 2. TTFA measurements (5 runs with spacing) ──
            System.out.println("── TTFA Measurements (kugel-1-turbo) ──");
            String[] texts = { SHORT, MEDIUM, LONG, SHORT, MEDIUM };
            List<Double> ttfas = new ArrayList<>();

            for (int i = 0; i < texts.length; i++) {
                String text = texts[i];
                double ttfa = measureTTFA(client, text);
                ttfas.add(ttfa);
                System.out.printf("  Run %d [%3d chars] TTFA: %6.1f ms%n", i + 1, text.length(), ttfa);
                Thread.sleep(2000);
            }
            System.out.println();
            printStats("TTFA", ttfas);
            System.out.println();

            // ── 3. Full generation with server-side metrics ──
            Thread.sleep(3000);
            System.out.println("── Full generation ──");
            long genStart = System.nanoTime();
            AudioResponse resp = client.tts().generate(
                    GenerateRequest.builder(MEDIUM).modelId("kugel-1-turbo").language("en").build()
            );
            double wallMs = (System.nanoTime() - genStart) / 1_000_000.0;
            System.out.printf("  Wall: %6.1f ms | Audio duration: %6.1f ms | Server gen: %6.1f ms | RTF: %.3f | Samples: %d%n",
                    wallMs, resp.getDurationMs(), resp.getGenerationMs(), resp.getRtf(), resp.getTotalSamples());

            Path wavPath = Path.of("java_sdk_test.wav");
            resp.saveWav(wavPath);
            System.out.printf("  Saved %s (%d bytes PCM)%n", wavPath, resp.getAudio().length);
        }

        System.out.println();
        System.out.println("=== Done ===");
    }

    private static double measureTTFA(KugelAudio client, String text) {
        AtomicLong firstChunkNano = new AtomicLong();
        long startNano = System.nanoTime();

        client.tts().stream(
                GenerateRequest.builder(text).modelId("kugel-1-turbo").language("en").build(),
                new StreamCallbacks() {
                    @Override
                    public void onChunk(AudioChunk chunk) {
                        firstChunkNano.compareAndSet(0, System.nanoTime());
                    }
                },
                false
        );

        long first = firstChunkNano.get();
        if (first == 0) return -1;
        return (first - startNano) / 1_000_000.0;
    }

    private static void printStats(String label, List<Double> values) {
        if (values.isEmpty()) return;
        double min = values.stream().mapToDouble(d -> d).min().orElse(0);
        double max = values.stream().mapToDouble(d -> d).max().orElse(0);
        double avg = values.stream().mapToDouble(d -> d).average().orElse(0);
        List<Double> sorted = values.stream().sorted().toList();
        double median = sorted.get(sorted.size() / 2);
        System.out.printf("  %s — Min: %.1f ms | Median: %.1f ms | Avg: %.1f ms | Max: %.1f ms  (n=%d)%n",
                label, min, median, avg, max, values.size());
    }
}
