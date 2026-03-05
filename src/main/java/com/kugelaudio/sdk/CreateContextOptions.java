package com.kugelaudio.sdk;

/**
 * Options for creating a context within a multi-context session.
 */
public final class CreateContextOptions {

    private final Integer voiceId;
    private final Double cfgScale;
    private final Integer maxNewTokens;

    private CreateContextOptions(Builder builder) {
        this.voiceId = builder.voiceId;
        this.cfgScale = builder.cfgScale;
        this.maxNewTokens = builder.maxNewTokens;
    }

    public Integer getVoiceId() { return voiceId; }
    public Double getCfgScale() { return cfgScale; }
    public Integer getMaxNewTokens() { return maxNewTokens; }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private Integer voiceId;
        private Double cfgScale;
        private Integer maxNewTokens;

        public Builder voiceId(int voiceId) { this.voiceId = voiceId; return this; }
        public Builder cfgScale(double cfgScale) { this.cfgScale = cfgScale; return this; }
        public Builder maxNewTokens(int maxNewTokens) { this.maxNewTokens = maxNewTokens; return this; }

        public CreateContextOptions build() {
            return new CreateContextOptions(this);
        }
    }
}
