package com.kugelaudio.sdk;

import com.kugelaudio.sdk.internal.HttpHelper;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Main entry point for the KugelAudio Java SDK.
 * Provides access to TTS generation, voice management, and model listing.
 *
 * <h3>Quick Start</h3>
 * <pre>{@code
 * // Create a client
 * KugelAudio client = new KugelAudio(
 *     KugelAudioOptions.builder("your-api-key").build()
 * );
 *
 * // Generate speech
 * AudioResponse response = client.tts().generate(
 *     GenerateRequest.builder("Hello, world!").voiceId(123).build()
 * );
 * response.saveWav(Path.of("output.wav"));
 *
 * // Stream speech with callbacks
 * client.tts().stream(
 *     GenerateRequest.builder("Hello!").voiceId(123).build(),
 *     new StreamCallbacks() {
 *         public void onChunk(AudioChunk chunk) {
 *             // Process raw PCM16 audio in real-time
 *         }
 *     }
 * );
 *
 * // Clean up
 * client.close();
 * }</pre>
 *
 * <h3>Environment Variable</h3>
 * <p>You can also create a client using the {@code KUGELAUDIO_API_KEY} environment variable:</p>
 * <pre>{@code
 * KugelAudio client = KugelAudio.fromEnv();
 * }</pre>
 */
public final class KugelAudio implements AutoCloseable {

    private final KugelAudioOptions options;
    private final HttpClient httpClient;
    private final HttpHelper httpHelper;
    private final ModelsResource models;
    private final VoicesResource voices;
    private final TTSResource tts;

    /**
     * Creates a new KugelAudio client with the given options.
     *
     * <p>By default, a WebSocket connection for TTS is established in the
     * background immediately, so the first {@code tts().generate()} or
     * {@code tts().stream()} call doesn't pay the ~150-300ms handshake cost.
     * Disable this with {@link KugelAudioOptions.Builder#autoConnect(boolean)}.
     */
    public KugelAudio(KugelAudioOptions options) {
        this.options = options;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(options.getTimeout())
                .build();
        this.httpHelper = new HttpHelper(httpClient, options);
        this.models = new ModelsResource(httpHelper);
        this.voices = new VoicesResource(httpHelper);
        this.tts = new TTSResource(options, httpClient);

        if (options.isAutoConnect()) {
            tts.startEagerConnect();
        }
    }

    /**
     * Creates a client from the {@code KUGELAUDIO_API_KEY} environment variable.
     *
     * @throws KugelAudioException if the environment variable is not set
     */
    public static KugelAudio fromEnv() {
        String apiKey = System.getenv("KUGELAUDIO_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            throw new KugelAudioException("KUGELAUDIO_API_KEY environment variable is not set");
        }
        return new KugelAudio(KugelAudioOptions.builder(apiKey).build());
    }

    /**
     * Creates a client and <em>synchronously</em> waits for the WebSocket connection
     * to be ready before returning. Useful when you need to guarantee the connection
     * is established. For most use cases the default constructor (which auto-connects
     * in the background) is sufficient.
     */
    public static KugelAudio createConnected(KugelAudioOptions options) {
        KugelAudio client = new KugelAudio(options);
        client.tts().connect();
        return client;
    }

    /** Access the Models resource (list available TTS models). */
    public ModelsResource models() { return models; }

    /** Access the Voices resource (CRUD, references, publishing). */
    public VoicesResource voices() { return voices; }

    /** Access the TTS resource (generate, stream, streaming sessions). */
    public TTSResource tts() { return tts; }

    /**
     * Pre-establishes the WebSocket connection for TTS.
     */
    public void connect() {
        tts.connect();
    }

    /** Returns true if a pooled TTS WebSocket connection is active. */
    public boolean isConnected() {
        return tts.isConnected();
    }

    /**
     * Creates a streaming session for incremental text-to-speech.
     * Use this when text arrives token-by-token (e.g. from an LLM).
     */
    public StreamingSession streamingSession(StreamConfig config, StreamCallbacks callbacks) {
        StreamingSession session = new StreamingSession(options, httpClient, config, callbacks);
        session.connect();
        return session;
    }

    /**
     * Creates a multi-context streaming session for concurrent speakers.
     * Supports up to 5 independent audio contexts over a single WebSocket.
     */
    public MultiContextSession multiContextSession(MultiContextConfig config) {
        return new MultiContextSession(options, httpClient, config);
    }

    @Override
    public void close() {
        tts.close();
    }
}
