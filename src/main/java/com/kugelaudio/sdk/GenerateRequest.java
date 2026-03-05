package com.kugelaudio.sdk;

/**
 * Parameters for a TTS generation request.
 *
 * <pre>{@code
 * var request = GenerateRequest.builder("Hello, world!")
 *     .voiceId(123)
 *     .modelId("kugel-1-turbo")
 *     .sampleRate(24000)
 *     .build();
 * }</pre>
 */
public final class GenerateRequest {

    private final String text;
    private final String modelId;
    private final Integer voiceId;
    private final Double cfgScale;
    private final Integer maxNewTokens;
    private final Integer sampleRate;
    private final Boolean normalize;
    private final String language;
    private final Boolean wordTimestamps;

    private GenerateRequest(Builder builder) {
        this.text = builder.text;
        this.modelId = builder.modelId;
        this.voiceId = builder.voiceId;
        this.cfgScale = builder.cfgScale;
        this.maxNewTokens = builder.maxNewTokens;
        this.sampleRate = builder.sampleRate;
        this.normalize = builder.normalize;
        this.language = builder.language;
        this.wordTimestamps = builder.wordTimestamps;
    }

    public String getText() { return text; }
    public String getModelId() { return modelId; }
    public Integer getVoiceId() { return voiceId; }
    public Double getCfgScale() { return cfgScale; }
    public Integer getMaxNewTokens() { return maxNewTokens; }
    public Integer getSampleRate() { return sampleRate; }
    public Boolean getNormalize() { return normalize; }
    public String getLanguage() { return language; }
    public Boolean getWordTimestamps() { return wordTimestamps; }

    public static Builder builder(String text) {
        return new Builder(text);
    }

    public static final class Builder {
        private final String text;
        private String modelId = "kugel-1-turbo";
        private Integer voiceId;
        private Double cfgScale = 2.0;
        private Integer maxNewTokens = 2048;
        private Integer sampleRate = 24000;
        private Boolean normalize = true;
        private String language;
        private Boolean wordTimestamps;

        private Builder(String text) {
            if (text == null || text.isBlank()) {
                throw new IllegalArgumentException("text must not be blank");
            }
            this.text = text;
        }

        public Builder modelId(String modelId) { this.modelId = modelId; return this; }
        public Builder voiceId(int voiceId) { this.voiceId = voiceId; return this; }
        public Builder cfgScale(double cfgScale) { this.cfgScale = cfgScale; return this; }
        public Builder maxNewTokens(int maxNewTokens) { this.maxNewTokens = maxNewTokens; return this; }
        public Builder sampleRate(int sampleRate) { this.sampleRate = sampleRate; return this; }
        public Builder normalize(boolean normalize) { this.normalize = normalize; return this; }
        public Builder language(String language) { this.language = language; return this; }
        public Builder wordTimestamps(boolean wordTimestamps) { this.wordTimestamps = wordTimestamps; return this; }

        public GenerateRequest build() {
            return new GenerateRequest(this);
        }
    }
}
