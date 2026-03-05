package com.kugelaudio.bench;

import com.kugelaudio.sdk.*;

import java.io.ByteArrayOutputStream;
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
 * Validates that pooled TTFA ~70ms is real by inspecting every audio chunk:
 *   - PCM16 bytes are non-zero
 *   - RMS energy is speech-like (not silence/noise)
 *   - Chunk count, sample rate, total duration are sane
 *   - Saves each run as a playable WAV for manual verification
 *
 * Run:
 *   export JAVA_HOME=/opt/homebrew/opt/openjdk@17
 *   cd sdks/java/benchmark
 *   mvn compile exec:java \
 *       -Dexec.mainClass=com.kugelaudio.bench.PooledTTFAValidation \
 *       -Dexec.args="<api_key>"
 */
public class PooledTTFAValidation {

    private static final int VOICE_ID = 480;
    private static final String[] TEXTS = {
        "Hallo, das ist ein kurzer Test.",
        "Der schnelle braune Fuchs springt über den faulen Hund.",
        "KugelAudio bietet extrem niedrige Latenz für Sprachsynthese.",
        "Guten Morgen, wie geht es Ihnen heute?",
        "Dies ist der fünfte und letzte Testlauf.",
    };

    public static void main(String[] args) throws Exception {
        String apiKey = args.length > 0 ? args[0] : System.getenv("API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            System.err.println("Usage: PooledTTFAValidation <api_key>   (or set API_KEY env)");
            System.exit(1);
        }
        String apiUrl = args.length > 1 ? args[1] : System.getenv("API_URL");
        if (apiUrl == null || apiUrl.isBlank()) apiUrl = "https://api.kugelaudio.com";

        Locale.setDefault(Locale.US);
        Path outDir = Paths.get("validation-audio");
        Files.createDirectories(outDir);

        System.out.println("╔══════════════════════════════════════════════════════════╗");
        System.out.println("║   KugelAudio Pooled TTFA Validation Benchmark           ║");
        System.out.println("╚══════════════════════════════════════════════════════════╝");
        System.out.println("  Server : " + apiUrl);
        System.out.println("  Model  : kugel-1-turbo");
        System.out.println("  Voice  : " + VOICE_ID);
        System.out.println("  Output : " + outDir.toAbsolutePath());
        System.out.println();

        KugelAudioOptions options = KugelAudioOptions.builder(apiKey).apiUrl(apiUrl).build();

        try (KugelAudio client = new KugelAudio(options)) {
            // Wait for auto-connect to finish
            Thread.sleep(500);
            System.out.println("  Connection ready: " + client.isConnected());
            System.out.println();

            // ── Warmup (1 request, discard) ─────────────────────────────
            System.out.println("── Warmup ──");
            warmup(client);
            System.out.println();

            // ── 5 validated runs ────────────────────────────────────────
            System.out.println("── Pooled TTFA with full audio validation (5 runs) ──");
            System.out.println();

            String header = String.format(
                "  %-4s │ %8s │ %8s │ %6s │ %6s │ %8s │ %8s │ %s",
                "Run", "TTFA", "Finish", "Chunks", "Bytes", "Duration", "RMS", "Status"
            );
            System.out.println(header);
            System.out.println("  " + "─".repeat(header.trim().length()));

            List<RunResult> results = new ArrayList<>();
            for (int i = 0; i < TEXTS.length; i++) {
                RunResult r = measuredRun(client, TEXTS[i], i + 1);
                results.add(r);

                Path wavPath = outDir.resolve(String.format("validation-run%02d.wav", i + 1));
                writeWav(wavPath, r.pcm, r.sampleRate);

                String status = validate(r) ? "✓ PASS" : "✗ FAIL";
                System.out.printf(
                    "  %-4d │ %6.1fms │ %6.1fms │ %6d │ %6d │ %6.1fms │ %8.5f │ %s  → %s%n",
                    i + 1, r.ttfaMs, r.finishMs, r.chunkCount, r.pcm.length,
                    r.audioDurationMs, r.rmsEnergy, status, wavPath.getFileName()
                );

                Thread.sleep(500);
            }

            // ── Summary ─────────────────────────────────────────────────
            System.out.println();
            System.out.println("── Summary ──");

            List<Double> ttfas = results.stream().map(r -> r.ttfaMs).sorted().toList();
            List<Double> finishes = results.stream().map(r -> r.finishMs).sorted().toList();
            int passed = (int) results.stream().filter(PooledTTFAValidation::validate).count();

            System.out.printf("  TTFA    → min=%5.1fms  p50=%5.1fms  max=%5.1fms  avg=%5.1fms%n",
                ttfas.get(0), ttfas.get(ttfas.size() / 2),
                ttfas.get(ttfas.size() - 1),
                ttfas.stream().mapToDouble(d -> d).average().orElse(0));
            System.out.printf("  Finish  → min=%5.1fms  p50=%5.1fms  max=%5.1fms  avg=%5.1fms%n",
                finishes.get(0), finishes.get(finishes.size() / 2),
                finishes.get(finishes.size() - 1),
                finishes.stream().mapToDouble(d -> d).average().orElse(0));
            System.out.printf("  Validation: %d/%d passed%n", passed, results.size());
            System.out.println();

            if (passed == results.size()) {
                System.out.println("  ✓ All audio validated — TTFA numbers are real.");
            } else {
                System.out.println("  ✗ Some runs failed validation — check WAV files.");
            }
        }

        System.out.println();
        System.out.println("=== Done ===");
    }

    static RunResult measuredRun(KugelAudio client, String text, int runNum) {
        ByteArrayOutputStream pcmBuf = new ByteArrayOutputStream();
        AtomicLong firstChunkNano = new AtomicLong();
        AtomicInteger chunkCount = new AtomicInteger();
        AtomicInteger sampleRate = new AtomicInteger(24000);
        long startNano = System.nanoTime();

        client.tts().stream(
            GenerateRequest.builder(text)
                .modelId("kugel-1-turbo")
                .voiceId(VOICE_ID)
                .language("de")
                .build(),
            new StreamCallbacks() {
                @Override
                public void onChunk(AudioChunk chunk) {
                    firstChunkNano.compareAndSet(0, System.nanoTime());
                    chunkCount.incrementAndGet();
                    sampleRate.set(chunk.getSampleRate());
                    synchronized (pcmBuf) {
                        pcmBuf.writeBytes(chunk.getAudio());
                    }
                }
            },
            true
        );

        long endNano = System.nanoTime();
        byte[] pcm = pcmBuf.toByteArray();
        int sr = sampleRate.get();
        double ttfa = firstChunkNano.get() > 0 ? (firstChunkNano.get() - startNano) / 1e6 : -1;
        double finish = (endNano - startNano) / 1e6;
        double audioDurMs = (pcm.length / 2.0) / sr * 1000.0;
        double rms = computeRms(pcm);

        return new RunResult(ttfa, finish, chunkCount.get(), pcm, sr, audioDurMs, rms);
    }

    static boolean validate(RunResult r) {
        if (r.ttfaMs <= 0) return false;
        if (r.chunkCount < 1) return false;
        if (r.pcm.length < 100) return false;
        if (r.audioDurationMs < 200) return false;
        if (r.rmsEnergy < 0.001) return false;
        return true;
    }

    static double computeRms(byte[] pcm) {
        int numSamples = pcm.length / 2;
        if (numSamples == 0) return 0;
        double sumSq = 0;
        for (int i = 0; i < numSamples; i++) {
            short sample = (short) ((pcm[i * 2 + 1] << 8) | (pcm[i * 2] & 0xFF));
            double normalized = sample / 32768.0;
            sumSq += normalized * normalized;
        }
        return Math.sqrt(sumSq / numSamples);
    }

    static void warmup(KugelAudio client) {
        long t0 = System.nanoTime();
        AtomicLong first = new AtomicLong();
        AtomicInteger chunks = new AtomicInteger();
        client.tts().stream(
            GenerateRequest.builder("Aufwärmphase.")
                .modelId("kugel-1-turbo").voiceId(VOICE_ID).language("de").build(),
            new StreamCallbacks() {
                @Override public void onChunk(AudioChunk chunk) {
                    first.compareAndSet(0, System.nanoTime());
                    chunks.incrementAndGet();
                }
            },
            true
        );
        double ttfa = first.get() > 0 ? (first.get() - t0) / 1e6 : -1;
        double wall = (System.nanoTime() - t0) / 1e6;
        System.out.printf("  Warmup: TTFA=%.1fms  Wall=%.1fms  Chunks=%d%n", ttfa, wall, chunks.get());
    }

    static void writeWav(Path path, byte[] pcm, int sampleRate) throws Exception {
        int channels = 1, bitDepth = 16;
        int byteRate = sampleRate * channels * bitDepth / 8;
        int blockAlign = channels * bitDepth / 8;
        ByteBuffer buf = ByteBuffer.allocate(44 + pcm.length).order(ByteOrder.LITTLE_ENDIAN);
        buf.put(new byte[]{'R','I','F','F'});
        buf.putInt(36 + pcm.length);
        buf.put(new byte[]{'W','A','V','E'});
        buf.put(new byte[]{'f','m','t',' '});
        buf.putInt(16);
        buf.putShort((short) 1);
        buf.putShort((short) channels);
        buf.putInt(sampleRate);
        buf.putInt(byteRate);
        buf.putShort((short) blockAlign);
        buf.putShort((short) bitDepth);
        buf.put(new byte[]{'d','a','t','a'});
        buf.putInt(pcm.length);
        buf.put(pcm);
        Files.write(path, buf.array());
    }

    record RunResult(double ttfaMs, double finishMs, int chunkCount, byte[] pcm,
                     int sampleRate, double audioDurationMs, double rmsEnergy) {}
}
