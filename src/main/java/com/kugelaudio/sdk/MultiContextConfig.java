package com.kugelaudio.sdk;

/**
 * Session-level configuration for multi-context streaming.
 */
public final class MultiContextConfig {

    private final Integer sampleRate;
    private final Boolean normalize;
    private final String language;
    private final Boolean wordTimestamps;

    private MultiContextConfig(Builder builder) {
        this.sampleRate = builder.sampleRate;
        this.normalize = builder.normalize;
        this.language = builder.language;
        this.wordTimestamps = builder.wordTimestamps;
    }

    public Integer getSampleRate() { return sampleRate; }
    public Boolean getNormalize() { return normalize; }
    public String getLanguage() { return language; }
    public Boolean getWordTimestamps() { return wordTimestamps; }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private Integer sampleRate = 24000;
        private Boolean normalize = true;
        private String language;
        private Boolean wordTimestamps;

        public Builder sampleRate(int sampleRate) { this.sampleRate = sampleRate; return this; }
        public Builder normalize(boolean normalize) { this.normalize = normalize; return this; }
        /**
         * ISO 639-1 language code for text normalization (e.g., "de", "en", "fr").
         * If not set and normalize is true, the server auto-detects the language,
         * which adds ~60-150ms to time-to-first-audio.
         */
        public Builder language(String language) { this.language = language; return this; }
        public Builder wordTimestamps(boolean wordTimestamps) { this.wordTimestamps = wordTimestamps; return this; }

        public MultiContextConfig build() {
            return new MultiContextConfig(this);
        }
    }
}
