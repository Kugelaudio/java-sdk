package com.kugelaudio.sdk;

import java.util.List;

/**
 * Session-level configuration for multi-context streaming.
 */
public final class MultiContextConfig {

    private final Integer sampleRate;
    private final String outputFormat;
    private final Boolean normalize;
    private final String language;
    private final Boolean wordTimestamps;
    private final Double temperature;
    private final Long projectId;
    private final List<Integer> dictionaryIds;

    private MultiContextConfig(Builder builder) {
        this.sampleRate = builder.sampleRate;
        this.outputFormat = builder.outputFormat;
        this.normalize = builder.normalize;
        this.language = builder.language;
        this.wordTimestamps = builder.wordTimestamps;
        this.temperature = builder.temperature;
        this.projectId = builder.projectId;
        this.dictionaryIds = builder.dictionaryIds;
    }

    public Integer getSampleRate() { return sampleRate; }
    public String getOutputFormat() { return outputFormat; }
    public Boolean getNormalize() { return normalize; }
    public String getLanguage() { return language; }
    public Boolean getWordTimestamps() { return wordTimestamps; }
    public Double getTemperature() { return temperature; }
    public Long getProjectId() { return projectId; }
    public List<Integer> getDictionaryIds() { return dictionaryIds; }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private Integer sampleRate = 24000;
        private String outputFormat;
        private Boolean normalize = true;
        private String language;
        private Boolean wordTimestamps;
        private Double temperature;
        private Long projectId;
        private List<Integer> dictionaryIds;

        public Builder sampleRate(int sampleRate) { this.sampleRate = sampleRate; return this; }

        /**
         * Combined codec+rate token, e.g. {@code "ulaw_8000"} / {@code "alaw_8000"} /
         * {@code "pcm_8000"}. Opt-in, set-once per session. Absent ⇒ legacy PCM16.
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

        /**
         * Sampling variance. Range [0.0, 1.0]. 0 = most stable (near-greedy),
         * 1 = most variance. Default (when not set): 0.5 server-side.
         */
        public Builder temperature(double temperature) { this.temperature = temperature; return this; }

        /**
         * Project whose pronunciation dictionaries apply at synthesis.
         *
         * <p>Required for any dictionary to apply and for a non-empty
         * {@link #dictionaryIds}. Not set (default): no project is sent.
         */
        public Builder projectId(long projectId) { this.projectId = projectId; return this; }

        /**
         * Per-session dictionary selection, applied to every context.
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

        public MultiContextConfig build() {
            return new MultiContextConfig(this);
        }
    }
}
