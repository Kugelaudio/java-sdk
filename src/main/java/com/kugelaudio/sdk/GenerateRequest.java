package com.kugelaudio.sdk;

import java.util.List;

/**
 * Parameters for a TTS generation request.
 *
 * <pre>{@code
 * var request = GenerateRequest.builder("Hello, world!")
 *     .voiceId(123)
 *     .modelId("kugel-3")
 *     .sampleRate(24000)
 *     .build();
 * }</pre>
 */
public final class GenerateRequest {

    private final String text;
    private final String modelId;
    private final Integer voiceId;
    private final Double cfgScale;
    private final Double temperature;
    private final Integer maxNewTokens;
    private final Integer sampleRate;
    private final String outputFormat;
    private final Boolean normalize;
    private final String language;
    private final Boolean wordTimestamps;
    private final Double speed;
    private final Long projectId;
    private final List<Integer> dictionaryIds;

    private GenerateRequest(Builder builder) {
        this.text = builder.text;
        this.modelId = builder.modelId;
        this.voiceId = builder.voiceId;
        this.cfgScale = builder.cfgScale;
        this.temperature = builder.temperature;
        this.maxNewTokens = builder.maxNewTokens;
        this.sampleRate = builder.sampleRate;
        this.outputFormat = builder.outputFormat;
        this.normalize = builder.normalize;
        this.language = builder.language;
        this.wordTimestamps = builder.wordTimestamps;
        this.speed = builder.speed;
        this.projectId = builder.projectId;
        this.dictionaryIds = builder.dictionaryIds;
    }

    public String getText() { return text; }
    public String getModelId() { return modelId; }
    public Integer getVoiceId() { return voiceId; }
    public Double getCfgScale() { return cfgScale; }
    public Double getTemperature() { return temperature; }
    public Integer getMaxNewTokens() { return maxNewTokens; }
    public Integer getSampleRate() { return sampleRate; }
    public String getOutputFormat() { return outputFormat; }
    public Boolean getNormalize() { return normalize; }
    public String getLanguage() { return language; }
    public Boolean getWordTimestamps() { return wordTimestamps; }
    public Double getSpeed() { return speed; }
    public Long getProjectId() { return projectId; }
    public List<Integer> getDictionaryIds() { return dictionaryIds; }

    public static Builder builder(String text) {
        return new Builder(text);
    }

    public static final class Builder {
        private final String text;
        private String modelId = "kugel-3";
        private Integer voiceId;
        private Double cfgScale = 2.0;
        private Double temperature;
        private Integer maxNewTokens = 2048;
        private Integer sampleRate = 24000;
        private String outputFormat;
        private Boolean normalize = true;
        private String language;
        private Boolean wordTimestamps;
        private Double speed;
        private Long projectId;
        private List<Integer> dictionaryIds;

        private Builder(String text) {
            if (text == null || text.isBlank()) {
                throw new IllegalArgumentException("text must not be blank");
            }
            this.text = text;
        }

        public Builder modelId(String modelId) { this.modelId = modelId; return this; }
        public Builder voiceId(int voiceId) { this.voiceId = voiceId; return this; }
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
         * {@code "pcm_8000"}. Opt-in; when set it is authoritative and must not
         * contradict {@link #sampleRate}. Absent ⇒ legacy PCM16 at sampleRate.
         */
        public Builder outputFormat(String outputFormat) { this.outputFormat = outputFormat; return this; }
        public Builder normalize(boolean normalize) { this.normalize = normalize; return this; }
        public Builder language(String language) { this.language = language; return this; }
        public Builder wordTimestamps(boolean wordTimestamps) { this.wordTimestamps = wordTimestamps; return this; }

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
         * Per-request dictionary selection.
         *
         * <p>Not set (default): all <em>active</em> dictionaries of the project
         * apply, filtered by language. An empty list: no dictionary applies to
         * this request. A list of dictionary IDs: exactly those dictionaries
         * apply — even inactive ones — bypassing the language filter.
         *
         * <p>IDs must belong to the request's project; unknown IDs are rejected
         * with a validation error before generation starts.
         */
        public Builder dictionaryIds(List<Integer> dictionaryIds) {
            this.dictionaryIds = List.copyOf(dictionaryIds);
            return this;
        }

        public GenerateRequest build() {
            return new GenerateRequest(this);
        }
    }
}
