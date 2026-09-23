package com.kugelaudio.sdk;

/**
 * Generation parameters changeable mid-connection via
 * {@link StreamingSession#updateSettings(SettingsUpdate)} /
 * {@link MultiContextSession#updateSettings(SettingsUpdate)} (KUG-1166).
 *
 * <p>Every field is optional; an update changes only the fields it carries.
 * Identity / audio-format fields ({@code voiceId}, {@code modelId},
 * {@code sampleRate}, {@code outputFormat}, {@code projectId}, {@code dictionaryIds})
 * are NOT here — they are fixed for the connection's lifetime and the server
 * rejects them in an update.
 *
 * <pre>{@code
 * EffectiveSettings effective = session.updateSettings(
 *     SettingsUpdate.builder().cfgScale(1.5).speed(1.1).build());
 * }</pre>
 */
public final class SettingsUpdate {

    private final Double cfgScale;
    private final Double temperature;
    private final Double speed;
    private final Integer maxNewTokens;
    private final String language;
    private final Boolean normalize;

    private SettingsUpdate(Builder b) {
        this.cfgScale = b.cfgScale;
        this.temperature = b.temperature;
        this.speed = b.speed;
        this.maxNewTokens = b.maxNewTokens;
        this.language = b.language;
        this.normalize = b.normalize;
    }

    /** Classifier-free guidance scale (0.0–10.0), or {@code null} to leave unchanged. */
    public Double getCfgScale() { return cfgScale; }

    /** Sampling variance (0.0–1.0), or {@code null} to leave unchanged. */
    public Double getTemperature() { return temperature; }

    /** Playback speed multiplier (0.8–1.2), or {@code null} to leave unchanged. */
    public Double getSpeed() { return speed; }

    /** Maximum tokens per generation (1–2048), or {@code null} to leave unchanged. */
    public Integer getMaxNewTokens() { return maxNewTokens; }

    /** Language code for normalization (e.g. {@code "de"}), or {@code null}. */
    public String getLanguage() { return language; }

    /** Whether text normalization is enabled, or {@code null} to leave unchanged. */
    public Boolean getNormalize() { return normalize; }

    public static Builder builder() { return new Builder(); }

    /** Fluent builder for {@link SettingsUpdate}. */
    public static final class Builder {
        private Double cfgScale;
        private Double temperature;
        private Double speed;
        private Integer maxNewTokens;
        private String language;
        private Boolean normalize;

        public Builder cfgScale(double cfgScale) { this.cfgScale = cfgScale; return this; }
        public Builder temperature(double temperature) { this.temperature = temperature; return this; }
        public Builder speed(double speed) { this.speed = speed; return this; }
        public Builder maxNewTokens(int maxNewTokens) { this.maxNewTokens = maxNewTokens; return this; }
        public Builder language(String language) { this.language = language; return this; }
        public Builder normalize(boolean normalize) { this.normalize = normalize; return this; }

        public SettingsUpdate build() { return new SettingsUpdate(this); }
    }
}
