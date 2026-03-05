package com.kugelaudio.sdk;

/**
 * Configuration for a streaming TTS session (text-in/audio-out).
 *
 * <pre>{@code
 * var config = StreamConfig.builder()
 *     .voiceId(123)
 *     .modelId("kugel-1-turbo")
 *     .sampleRate(24000)
 *     .build();
 * }</pre>
 */
public final class StreamConfig {

    private final Integer voiceId;
    private final String modelId;
    private final Double cfgScale;
    private final Integer maxNewTokens;
    private final Integer sampleRate;
    private final Boolean normalize;
    private final String language;
    private final Boolean wordTimestamps;
    private final Integer flushTimeoutMs;

    private StreamConfig(Builder builder) {
        this.voiceId = builder.voiceId;
        this.modelId = builder.modelId;
        this.cfgScale = builder.cfgScale;
        this.maxNewTokens = builder.maxNewTokens;
        this.sampleRate = builder.sampleRate;
        this.normalize = builder.normalize;
        this.language = builder.language;
        this.wordTimestamps = builder.wordTimestamps;
        this.flushTimeoutMs = builder.flushTimeoutMs;
    }

    public Integer getVoiceId() { return voiceId; }
    public String getModelId() { return modelId; }
    public Double getCfgScale() { return cfgScale; }
    public Integer getMaxNewTokens() { return maxNewTokens; }
    public Integer getSampleRate() { return sampleRate; }
    public Boolean getNormalize() { return normalize; }
    public String getLanguage() { return language; }
    public Boolean getWordTimestamps() { return wordTimestamps; }
    public Integer getFlushTimeoutMs() { return flushTimeoutMs; }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private Integer voiceId;
        private String modelId = "kugel-1-turbo";
        private Double cfgScale = 2.0;
        private Integer maxNewTokens = 2048;
        private Integer sampleRate = 24000;
        private Boolean normalize = true;
        private String language;
        private Boolean wordTimestamps;
        private Integer flushTimeoutMs;

        public Builder voiceId(int voiceId) { this.voiceId = voiceId; return this; }
        public Builder modelId(String modelId) { this.modelId = modelId; return this; }
        public Builder cfgScale(double cfgScale) { this.cfgScale = cfgScale; return this; }
        public Builder maxNewTokens(int maxNewTokens) { this.maxNewTokens = maxNewTokens; return this; }
        public Builder sampleRate(int sampleRate) { this.sampleRate = sampleRate; return this; }
        public Builder normalize(boolean normalize) { this.normalize = normalize; return this; }
        /**
         * ISO 639-1 language code for text normalization (e.g., "de", "en", "fr").
         * If not set and normalize is true, the server auto-detects the language,
         * which adds ~60-150ms to time-to-first-audio.
         */
        public Builder language(String language) { this.language = language; return this; }
        public Builder wordTimestamps(boolean wordTimestamps) { this.wordTimestamps = wordTimestamps; return this; }
        public Builder flushTimeoutMs(int flushTimeoutMs) { this.flushTimeoutMs = flushTimeoutMs; return this; }

        public StreamConfig build() {
            return new StreamConfig(this);
        }
    }
}
