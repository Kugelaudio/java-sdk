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
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * TTFA benchmark using com.kugelaudio:kugelaudio from Maven Central.
 *
 * Run:
 *   API_KEY=<key> [API_URL=<url>] mvn compile exec:java
 *   or: mvn compile exec:java -Dexec.args="<api_key> [api_url]"
 *
 * API_URL defaults to https://api.kugelaudio.com; point it at a self-hosted deployment to bench that.
 */
public class TTFABench {

    private static final String DEFAULT_API_URL = "https://api.kugelaudio.com";
    private static final String MODEL_ID    = "kugel-3";
    private static final int    VOICE_ID    = 1071;
    private static final String LANGUAGE    = "de";

    private static final String SHORT_TEXT  = "Hallo, das ist ein kurzer Test.";
    private static final String MEDIUM_TEXT = "Der schnelle braune Fuchs springt über den faulen Hund am Flussufer an einem warmen Sommernachmittag.";
    private static final String LONG_TEXT   = "In einer Welt, in der Technologie und Natur sich verflechten, stehen wir an einem Scheideweg zwischen Innovation und Tradition und suchen nach Balance in einer sich ständig wandelnden Welt.";

    private static final String USAGE = String.join("\n",
            "Usage: TTFABench <api_key> [api_url]",
            "   or: API_KEY=<key> [API_URL=<url>] mvn compile exec:java",
            "  API_KEY  KugelAudio API key (required; first argument or env)",
            "  API_URL  API base URL (optional; second argument or env; default " + DEFAULT_API_URL + ")");

    public static void main(String[] args) throws Exception { // NOSONAR – benchmark entry point
        String apiKey = args.length > 0 ? args[0] : System.getenv("API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            System.err.println(USAGE);
            System.exit(1);
        }
        String apiUrl = args.length > 1 ? args[1] : System.getenv("API_URL");
        if (apiUrl == null || apiUrl.isBlank()) apiUrl = DEFAULT_API_URL;

        Locale.setDefault(Locale.US);
        System.out.println("=== KugelAudio Java SDK — TTFA Benchmark ===");
        System.out.println("SDK version : com.kugelaudio:kugelaudio:" + System.getProperty("kugelaudio.version", "(see benchmark/pom.xml)"));
        System.out.println("Server      : " + apiUrl);
        System.out.println("Model/voice : " + MODEL_ID + " / " + VOICE_ID + " (" + LANGUAGE + ")");
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
            System.out.println("── TTFA Measurements (5 runs, " + MODEL_ID + ") ──");
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
                        GenerateRequest.builder(poolTexts[i]).modelId(MODEL_ID).voiceId(VOICE_ID).language(LANGUAGE).build(),
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
                    GenerateRequest.builder(MEDIUM_TEXT).modelId(MODEL_ID).voiceId(VOICE_ID).language(LANGUAGE).build()
            );
            double wallMs = (System.nanoTime() - genStart) / 1_000_000.0;
            Path fullGenPath = outDir.resolve("full-generate.wav");
            resp.saveWav(fullGenPath);
            System.out.printf("  Wall: %6.1f ms | Audio: %6.1f ms | Server gen: %6.1f ms | RTF: %.3f | Samples: %d  → %s%n",
                    wallMs, resp.getDurationMs(), resp.getGenerationMs(), resp.getRtf(),
                    resp.getTotalSamples(), fullGenPath.getFileName());

            // ── Chunking strategy comparison (StreamingSession) ───────────────
            //
            // Measures firstAudioFromFirstSend for different LLM-output chunking strategies
            // using /ws/tts/stream (StreamingSession).
            //
            // KEY INSIGHT: KugelAudio processes each flush() call as a separate TTS request.
            // Unlike single-request streaming (/ws/tts), the streaming session keeps context
            // alive between flushes — but each flush still incurs the full model TTFA overhead.
            // Very small chunks (word/clause level) therefore cause dramatically higher latency.
            //
            // RECOMMENDED strategy: send full sentences (or ≥30 chars), flush only at
            // sentence boundaries. This matches the server-side TextBuffer's design.
            Thread.sleep(2000);
            System.out.println("── Chunking strategy: firstAudioFromFirstSend (StreamingSession) ──");
            System.out.println("  (Simulates LLM token streaming with different flush strategies)");
            System.out.println("  NOTE: Each flush() is a separate model call — smaller chunks = higher latency.");
            System.out.println();

            chunkingStrategyBench(client);
        }

        System.out.println();
        System.out.println("=== Done ===");
    }

    /**
     * Measures firstAudioFromFirstSend for multiple chunking strategies using StreamingSession.
     *
     * <p>Strategies tested (same text, different flush granularity):
     * <ul>
     *   <li>FULL_TEXT — entire text in one send+flush (best possible, not realistic for LLM streams)</li>
     *   <li>SENTENCE — flush at sentence boundaries (recommended)</li>
     *   <li>MIN_CHARS_20 — flush when accumulated ≥20 chars</li>
     *   <li>CLAUSE — flush at comma/semicolon boundaries</li>
     *   <li>MIN_CHARS_10 — flush when accumulated ≥10 chars</li>
     *   <li>TWO_WORDS — flush every 2 words</li>
     *   <li>ONE_WORD — flush every word (worst case)</li>
     * </ul>
     */
    private static void chunkingStrategyBench(KugelAudio client)
            throws Exception {

        // Realistic LLM output text (German, same as used by customer report)
        String testText = "Guten Tag, ich heiße KugelAudio. Ich bin ein KI-Sprachassistent. Wie kann ich Ihnen heute helfen?";

        record Strategy(String name, List<String> chunks) {}

        // Sentence-level split
        String[] sentences = testText.split("(?<=[.!?])\\s+");

        // Clause-level: split at , ; . ! ?
        List<String> clauseChunks = new ArrayList<>();
        StringBuilder clauseBuf = new StringBuilder();
        for (char c : testText.toCharArray()) {
            clauseBuf.append(c);
            if (c == ',' || c == ';' || c == '.' || c == '!' || c == '?') {
                clauseChunks.add(clauseBuf.toString());
                clauseBuf.setLength(0);
            }
        }
        if (!clauseBuf.isEmpty()) clauseChunks.add(clauseBuf.toString());

        // MIN_CHARS_N: accumulate until threshold, then flush
        List<String> minChars20 = accumulate(testText, 20);
        List<String> minChars10 = accumulate(testText, 10);

        // Word-level
        List<String> wordChunks = new ArrayList<>();
        for (String w : testText.split(" ")) wordChunks.add(w + " ");

        // Two-word chunks
        List<String> twoWordChunks = new ArrayList<>();
        List<String> allWords = new ArrayList<>(wordChunks);
        for (int i = 0; i < allWords.size(); i += 2) {
            if (i + 1 < allWords.size()) {
                twoWordChunks.add(allWords.get(i) + allWords.get(i + 1));
            } else {
                twoWordChunks.add(allWords.get(i));
            }
        }

        List<Strategy> strategies = List.of(
            new Strategy("FULL_TEXT",       List.of(testText)),
            new Strategy("SENTENCE",        Arrays.asList(sentences)),
            new Strategy("MIN_CHARS_20",    minChars20),
            new Strategy("CLAUSE",          clauseChunks),
            new Strategy("MIN_CHARS_10",    minChars10),
            new Strategy("TWO_WORDS",       twoWordChunks),
            new Strategy("ONE_WORD",        wordChunks)
        );

        System.out.printf("  %-18s %10s %8s %s%n", "Strategy", "FirstAudio", "Chunks", "Text preview");
        System.out.println("  " + "─".repeat(70));

        for (Strategy s : strategies) {
            Thread.sleep(1500);
            double ttfa = measureStreamingSessionTTFA(client, s.chunks());
            System.out.printf("  %-18s %8.0f ms %6d  %s%n",
                    s.name(), ttfa, s.chunks().size(),
                    testText.substring(0, Math.min(40, testText.length())) + "…");
        }

        System.out.println();
        System.out.println("  Guidance:");
        System.out.println("  • FULL_TEXT is the fastest but not realistic for LLM streaming.");
        System.out.println("  • SENTENCE is the recommended strategy (best realistic latency).");
        System.out.println("  • MIN_CHARS_20+ is acceptable if sentence boundaries aren't available.");
        System.out.println("  • Anything smaller (word/clause) causes unacceptable latency overhead.");
        System.out.println("  • Keep the session open for the full turn; flush only at turn/sentence end.");
    }

    /** Splits text into chunks of at least {@code minChars} characters, breaking at spaces. */
    private static List<String> accumulate(String text, int minChars) {
        List<String> result = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        for (String word : text.split(" ")) {
            if (!buf.isEmpty()) buf.append(' ');
            buf.append(word);
            if (buf.length() >= minChars) {
                result.add(buf.toString() + " ");
                buf.setLength(0);
            }
        }
        if (!buf.isEmpty()) result.add(buf.toString());
        return result;
    }

    /**
     * Opens a StreamingSession, sends all chunks in order (each with flush=true),
     * and returns the time from the first send to the first audio chunk received.
     */
    private static double measureStreamingSessionTTFA(KugelAudio client, List<String> chunks)
            throws Exception {

        StreamConfig config = StreamConfig.builder()
                .modelId(MODEL_ID)
                .voiceId(VOICE_ID)
                .language(LANGUAGE)
                .build();

        AtomicLong firstAudioNano = new AtomicLong();
        AtomicLong firstSendNano = new AtomicLong();
        CountDownLatch sessionDone = new CountDownLatch(1);

        StreamCallbacks callbacks = new StreamCallbacks() {
            @Override
            public void onChunk(AudioChunk chunk) {
                firstAudioNano.compareAndSet(0, System.nanoTime());
            }

            @Override
            public void onSessionClosed(double totalAudioSeconds, int totalTextChunks, int totalAudioChunks) {
                sessionDone.countDown();
            }

            @Override
            public void onError(KugelAudioException error) {
                sessionDone.countDown();
            }
        };

        // streamingSession() auto-connects; use try-with-resources to close the WS
        try (StreamingSession session = client.streamingSession(config, callbacks)) {
            for (int i = 0; i < chunks.size(); i++) {
                if (i == 0) firstSendNano.set(System.nanoTime());
                session.send(chunks.get(i), true);  // flush after each chunk (simulates per-chunk strategy)
            }
        }

        // Wait for session_closed (server confirms all audio sent)
        sessionDone.await(30, TimeUnit.SECONDS);

        long first = firstAudioNano.get();
        long send  = firstSendNano.get();
        return (first > 0 && send > 0) ? (first - send) / 1_000_000.0 : -1;
    }

    private static double measureTTFAAndSave(KugelAudio client, String text, Path wavPath) throws IOException {
        AtomicLong firstChunkNano = new AtomicLong();
        ByteArrayOutputStream pcmBuf = new ByteArrayOutputStream();
        AtomicInteger sampleRate = new AtomicInteger(24000);
        long startNano = System.nanoTime();
        client.tts().stream(
                GenerateRequest.builder(text).modelId(MODEL_ID).voiceId(VOICE_ID).language(LANGUAGE).build(),
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
