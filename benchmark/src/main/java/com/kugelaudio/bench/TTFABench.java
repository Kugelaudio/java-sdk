package com.kugelaudio.bench;

import com.kugelaudio.sdk.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * TTFA benchmark using com.kugelaudio:kugelaudio from Maven Central.
 *
 * Run:
 *   mvn compile exec:java -Dexec.args="<api_key>"
 *   or: API_KEY=<key> mvn compile exec:java
 */
public class TTFABench {

    private static final String API_URL = "https://tts-staging.kugelaudio.com";
    private static final int    VOICE_ID    = 480;
    private static final String LANGUAGE    = "de";

    private static final String SHORT_TEXT  = "Hallo, das ist ein kurzer Test.";
    private static final String MEDIUM_TEXT = "Der schnelle braune Fuchs springt über den faulen Hund am Flussufer an einem warmen Sommernachmittag.";
    private static final String LONG_TEXT   = "In einer Welt, in der Technologie und Natur sich verflechten, stehen wir an einem Scheideweg zwischen Innovation und Tradition und suchen nach Balance in einer sich ständig wandelnden Welt.";

    public static void main(String[] args) throws Exception { // NOSONAR – benchmark entry point
        String apiKey = args.length > 0 ? args[0] : System.getenv("API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            System.err.println("Usage: TTFABench <api_key> [api_url]   (or set API_KEY / API_URL env)");
            System.exit(1);
        }
        String apiUrl = args.length > 1 ? args[1] : System.getenv("API_URL");
        if (apiUrl == null || apiUrl.isBlank()) apiUrl = API_URL;

        Locale.setDefault(Locale.US);
        System.out.println("=== KugelAudio Java SDK — TTFA Benchmark ===");
        System.out.println("SDK version : com.kugelaudio:kugelaudio:1.0.1 (Maven Central)");
        System.out.println("Server      : " + apiUrl);
        System.out.println();

        Path outDir = Paths.get("benchmark-audio");
        Files.createDirectories(outDir);
        System.out.println("Audio output : " + outDir.toAbsolutePath());
        System.out.println();

        KugelAudioOptions options = KugelAudioOptions.builder(apiKey).apiUrl(apiUrl).build();

        try (KugelAudio client = new KugelAudio(options)) {

            // ── Connectivity check ──────────────────────────────────────────
            System.out.println("── Verifying connectivity ──");
            List<Model> models = client.models().list();
            System.out.println("  Available models:");
            for (Model m : models) {
                System.out.println("    • " + m.getId() + " — " + m.getName());
            }
            System.out.println();

            // ── TTFA measurements ────────────────────────────────────────────
            System.out.println("── TTFA Measurements (5 runs, kugel-1-turbo) ──");
            String[] texts = { SHORT_TEXT, MEDIUM_TEXT, LONG_TEXT, SHORT_TEXT, MEDIUM_TEXT };
            List<Double> ttfas = new ArrayList<>();

            for (int i = 0; i < texts.length; i++) {
                Path wavPath = outDir.resolve(String.format("ttfa-run%02d.wav", i + 1));
                double ttfa = measureTTFAAndSave(client, texts[i], wavPath);
                ttfas.add(ttfa);
                System.out.printf("  Run %d [%3d chars]  TTFA: %6.1f ms  → %s%n",
                        i + 1, texts[i].length(), ttfa, wavPath.getFileName());
                Thread.sleep(2000);
            }

            System.out.println();
            printStats("TTFA", ttfas);
            System.out.println();

            // ── Connection-pooling benefit ───────────────────────────────────
            System.out.println("── Connection-pooling benefit (5 requests) ──");
            String[] poolTexts = {
                    "Hallo, das ist ein Test.",
                    "Der schnelle braune Fuchs springt.",
                    "KugelAudio bietet extrem niedrige Latenz.",
                    "Kurzer Test.",
                    "Noch ein Satz zur Konsistenzmessung."
            };

            List<Double> pooledTTFAs = new ArrayList<>();
            for (int i = 0; i < poolTexts.length; i++) {
                long t0 = System.nanoTime();
                AtomicLong first = new AtomicLong();
                ByteArrayOutputStream pcmBuf = new ByteArrayOutputStream();
                AtomicInteger sampleRate = new AtomicInteger(24000);
                client.tts().stream(
                        GenerateRequest.builder(poolTexts[i]).modelId("kugel-1-turbo").voiceId(VOICE_ID).language(LANGUAGE).build(),
                        new StreamCallbacks() {
                            @Override public void onChunk(AudioChunk chunk) {
                                first.compareAndSet(0, System.nanoTime());
                                sampleRate.set(chunk.getSampleRate());
                                synchronized (pcmBuf) {
                                    pcmBuf.writeBytes(chunk.getAudio());
                                }
                            }
                        },
                        true
                );
                double ttfa = first.get() > 0 ? (first.get() - t0) / 1_000_000.0 : -1;
                pooledTTFAs.add(ttfa);
                String label = i == 0 ? "(incl. connect)" : "(pooled)       ";
                Path wavPath = outDir.resolve(String.format("pooled-req%02d.wav", i + 1));
                writeWav(wavPath, pcmBuf.toByteArray(), sampleRate.get());
                System.out.printf("  Request %d %s  TTFA: %6.1f ms  → %s%n",
                        i + 1, label, ttfa, wavPath.getFileName());
                Thread.sleep(500);
            }

            System.out.println();
            printStats("Pooled TTFA (all)", pooledTTFAs);
            List<Double> warmPooled = pooledTTFAs.subList(1, pooledTTFAs.size());
            printStats("Pooled TTFA (excl. connect)", warmPooled);
            System.out.println();

            // ── Full generate (non-streaming) ────────────────────────────────
            Thread.sleep(2000);
            System.out.println("── Full generation (non-streaming) ──");
            long genStart = System.nanoTime();
            AudioResponse resp = client.tts().generate(
                    GenerateRequest.builder(MEDIUM_TEXT).modelId("kugel-1-turbo").voiceId(VOICE_ID).language(LANGUAGE).build()
            );
            double wallMs = (System.nanoTime() - genStart) / 1_000_000.0;
            Path fullGenPath = outDir.resolve("full-generate.wav");
            resp.saveWav(fullGenPath);
            System.out.printf("  Wall: %6.1f ms | Audio: %6.1f ms | Server gen: %6.1f ms | RTF: %.3f | Samples: %d  → %s%n",
                    wallMs, resp.getDurationMs(), resp.getGenerationMs(), resp.getRtf(),
                    resp.getTotalSamples(), fullGenPath.getFileName());
        }

        System.out.println();
        System.out.println("=== Done ===");
    }

    private static double measureTTFAAndSave(KugelAudio client, String text, Path wavPath) throws IOException {
        AtomicLong firstChunkNano = new AtomicLong();
        ByteArrayOutputStream pcmBuf = new ByteArrayOutputStream();
        AtomicInteger sampleRate = new AtomicInteger(24000);
        long startNano = System.nanoTime();
        client.tts().stream(
                GenerateRequest.builder(text).modelId("kugel-1-turbo").voiceId(VOICE_ID).language(LANGUAGE).build(),
                new StreamCallbacks() {
                    @Override public void onChunk(AudioChunk chunk) {
                        firstChunkNano.compareAndSet(0, System.nanoTime());
                        sampleRate.set(chunk.getSampleRate());
                        synchronized (pcmBuf) {
                            pcmBuf.writeBytes(chunk.getAudio());
                        }
                    }
                },
                false
        );
        writeWav(wavPath, pcmBuf.toByteArray(), sampleRate.get());
        long first = firstChunkNano.get();
        return first == 0 ? -1 : (first - startNano) / 1_000_000.0;
    }

    /** Writes raw 16-bit LE mono PCM bytes as a standard WAV file. */
    private static void writeWav(Path path, byte[] pcm, int sampleRate) throws IOException {
        int channels    = 1;
        int bitDepth    = 16;
        int byteRate    = sampleRate * channels * bitDepth / 8;
        int blockAlign  = channels * bitDepth / 8;
        int dataSize    = pcm.length;
        int headerSize  = 44;

        ByteBuffer buf = ByteBuffer.allocate(headerSize + dataSize).order(ByteOrder.LITTLE_ENDIAN);
        // RIFF chunk
        buf.put(new byte[]{'R','I','F','F'});
        buf.putInt(36 + dataSize);
        buf.put(new byte[]{'W','A','V','E'});
        // fmt sub-chunk
        buf.put(new byte[]{'f','m','t',' '});
        buf.putInt(16);
        buf.putShort((short) 1);           // PCM
        buf.putShort((short) channels);
        buf.putInt(sampleRate);
        buf.putInt(byteRate);
        buf.putShort((short) blockAlign);
        buf.putShort((short) bitDepth);
        // data sub-chunk
        buf.put(new byte[]{'d','a','t','a'});
        buf.putInt(dataSize);
        buf.put(pcm);

        Files.write(path, buf.array());
    }

    private static void printStats(String label, List<Double> values) {
        if (values.isEmpty()) return;
        List<Double> sorted = values.stream().filter(v -> v > 0).sorted().toList();
        if (sorted.isEmpty()) { System.out.printf("  %s — no valid samples%n", label); return; }
        double min    = sorted.get(0);
        double max    = sorted.get(sorted.size() - 1);
        double avg    = sorted.stream().mapToDouble(d -> d).average().orElse(0);
        double median = sorted.get(sorted.size() / 2);
        double p95    = sorted.get(Math.min((int) (sorted.size() * 0.95), sorted.size() - 1));
        System.out.printf(
                "  %-32s  min=%5.1f ms  p50=%5.1f ms  p95=%5.1f ms  avg=%5.1f ms  max=%5.1f ms  (n=%d)%n",
                label, min, median, p95, avg, max, sorted.size());
    }
}
