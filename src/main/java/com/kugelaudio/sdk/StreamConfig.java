package com.kugelaudio.sdk;

import java.util.Collections;
import java.util.List;

/**
 * Configuration for a streaming TTS session (text-in/audio-out).
 *
 * <p>The server accumulates LLM tokens internally and starts generation at natural sentence
 * boundaries. Use {@link Builder#chunkLengthSchedule(List)} to tune how eagerly the server
 * starts generating (smaller values = lower TTFA, less prosody context), or enable
 * {@link Builder#autoMode(boolean)} to start at the very first clean boundary.
 *
 * <pre>{@code
 * // Default — balanced TTFA vs. prosody quality
 * var config = StreamConfig.builder()
 *     .voiceId(123)
 *     .modelId("kugel-3")
 *     .build();
 *
 * // Optimise for low latency (ElevenLabs auto_mode equivalent)
 * var config = StreamConfig.builder()
 *     .voiceId(123)
 *     .autoMode(true)
 *     .chunkLengthSchedule(List.of(50, 100, 150, 250))
 *     .build();
 * }</pre>
 */
public final class StreamConfig {

    private final Integer voiceId;
    private final String modelId;
    private final Double cfgScale;
    private final Double temperature;
    private final Integer maxNewTokens;
    private final Integer sampleRate;
    private final String outputFormat;
    private final Boolean normalize;
    private final String language;
    private final Boolean wordTimestamps;
    private final Integer flushTimeoutMs;
    private final List<Integer> chunkLengthSchedule;
    private final Boolean autoMode;
    private final Double speed;
    private final Long projectId;
    private final List<Integer> dictionaryIds;

    private StreamConfig(Builder builder) {
        this.voiceId = builder.voiceId;
        this.modelId = builder.modelId;
        this.cfgScale = builder.cfgScale;
        this.temperature = builder.temperature;
        this.maxNewTokens = builder.maxNewTokens;
        this.sampleRate = builder.sampleRate;
        this.outputFormat = builder.outputFormat;
        this.normalize = builder.normalize;
        this.language = builder.language;
        this.wordTimestamps = builder.wordTimestamps;
        this.flushTimeoutMs = builder.flushTimeoutMs;
        this.chunkLengthSchedule = builder.chunkLengthSchedule;
        this.autoMode = builder.autoMode;
        this.speed = builder.speed;
        this.projectId = builder.projectId;
        this.dictionaryIds = builder.dictionaryIds;
    }

    public Integer getVoiceId() { return voiceId; }
    public String getModelId() { return modelId; }
    public Double getCfgScale() { return cfgScale; }
    public Double getTemperature() { return temperature; }
    public Integer getMaxNewTokens() { return maxNewTokens; }
    public Integer getSampleRate() { return sampleRate; }
    public String getOutputFormat() { return outputFormat; }
    public Boolean getNormalize() { return normalize; }
    public String getLanguage() { return language; }
    public Boolean getWordTimestamps() { return wordTimestamps; }
    public Integer getFlushTimeoutMs() { return flushTimeoutMs; }
    public List<Integer> getChunkLengthSchedule() { return chunkLengthSchedule; }
    public Boolean getAutoMode() { return autoMode; }
    public Double getSpeed() { return speed; }
    public Long getProjectId() { return projectId; }
    public List<Integer> getDictionaryIds() { return dictionaryIds; }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private Integer voiceId;
        private String modelId = "kugel-3";
        private Double cfgScale = 2.0;
        private Double temperature;
        private Integer maxNewTokens = 2048;
        private Integer sampleRate = 24000;
        private String outputFormat;
        private Boolean normalize = true;
        private String language;
        private Boolean wordTimestamps;
        private Integer flushTimeoutMs;
        private List<Integer> chunkLengthSchedule;
        private Boolean autoMode;
        private Double speed;
        private Long projectId;
        private List<Integer> dictionaryIds;

        public Builder voiceId(int voiceId) { this.voiceId = voiceId; return this; }
        public Builder modelId(String modelId) { this.modelId = modelId; return this; }

        /**
         * Classifier-free guidance scale. Clamped to [1.2, 2.5]. Default 2.0.
         */
        public Builder cfgScale(double cfgScale) {
            this.cfgScale = CfgScale.clamp(cfgScale);
            return this;
        }

        /**
         * Sampling variance. Range [0.0, 1.0]. 0 = most stable (near-greedy),
         * 1 = most variance. Default (when not set): 0.5 server-side.
         *
         * <p>Lower values produce more consistent reads across regenerations —
         * useful for stable voiceovers, IVR prompts, and e-learning.
         */
        public Builder temperature(double temperature) { this.temperature = temperature; return this; }
        public Builder maxNewTokens(int maxNewTokens) { this.maxNewTokens = maxNewTokens; return this; }
        public Builder sampleRate(int sampleRate) { this.sampleRate = sampleRate; return this; }

        /**
         * Combined codec+rate token, e.g. {@code "ulaw_8000"} / {@code "alaw_8000"} /
         * {@code "pcm_8000"}. Opt-in, set-once per session; authoritative when set
         * (must not contradict {@link #sampleRate}). Absent ⇒ legacy PCM16.
         */
        public Builder outputFormat(String outputFormat) { this.outputFormat = outputFormat; return this; }
        public Builder normalize(boolean normalize) { this.normalize = normalize; return this; }
        /**
         * ISO 639-1 language code for text normalization (e.g., "de", "en", "fr").
         * If not set, the server normalizes in the voice's primary language, falling
         * back to English. It does not detect the language from the text.
         */
        public Builder language(String language) { this.language = language; return this; }
        public Builder wordTimestamps(boolean wordTimestamps) { this.wordTimestamps = wordTimestamps; return this; }
        public Builder flushTimeoutMs(int flushTimeoutMs) { this.flushTimeoutMs = flushTimeoutMs; return this; }

        /**
         * Minimum buffer sizes (in characters) the server must accumulate before
         * auto-emitting each successive chunk.  Entry {@code i} applies to chunk
         * {@code i}; the last value is reused for all subsequent chunks.
         *
         * <p>Smaller values produce lower TTFA at the cost of less prosody context
         * for the model.  Larger values improve naturalness but increase TTFA.
         *
         * <p>Example schedules:
         * <ul>
         *   <li>{@code [50, 100, 150, 250]} — low-latency (fast first audio)</li>
         *   <li>{@code [120, 200, 300]} — high-quality prosody (default-like)</li>
         * </ul>
         *
         * @param schedule non-empty list of positive character counts
         */
        public Builder chunkLengthSchedule(List<Integer> schedule) {
            this.chunkLengthSchedule = Collections.unmodifiableList(schedule);
            return this;
        }

        /**
         * When {@code true}, the server starts generating audio at the very first
         * clean sentence boundary, regardless of {@code chunk_length_schedule}.
         * Equivalent to ElevenLabs' {@code auto_mode=true}.  Prioritises low TTFA;
         * may produce slightly less natural prosody on the first chunk.
         */
        public Builder autoMode(boolean autoMode) { this.autoMode = autoMode; return this; }

        /**
         * Playback speed multiplier (0.8 = slower, 1.0 = normal, 1.2 = faster).
         *
         * <p>Uses pitch-preserving time-stretching (WSOLA). Inline
         * {@code <prosody rate="...">} tags can also control speed per segment.
         * Supported range: [0.8, 1.2].
         */
        public Builder speed(double speed) { this.speed = speed; return this; }

        /**
         * Project whose pronunciation dictionaries apply at synthesis.
         *
         * <p>Required for any dictionary to apply and for a non-empty
         * {@link #dictionaryIds}. Not set (default): no project is sent.
         */
        public Builder projectId(long projectId) { this.projectId = projectId; return this; }

        /**
         * Per-session dictionary selection, applied to every turn.
         *
         * <p>Not set (default): all <em>active</em> dictionaries of the project
         * apply, filtered by language. An empty list: no dictionary applies.
         * A list of dictionary IDs: exactly those dictionaries apply — even
         * inactive ones — bypassing the language filter.
         */
        public Builder dictionaryIds(List<Integer> dictionaryIds) {
            this.dictionaryIds = List.copyOf(dictionaryIds);
            return this;
        }

        public StreamConfig build() {
            return new StreamConfig(this);
        }
    }
}
