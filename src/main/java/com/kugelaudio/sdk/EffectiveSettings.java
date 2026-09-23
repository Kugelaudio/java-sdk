package com.kugelaudio.sdk;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * The generation parameters in effect after an
 * {@link StreamingSession#updateSettings(SettingsUpdate)} call — the server's
 * echo (KUG-1166). Fields the session never set come back {@code null}
 * (e.g. {@code temperature}, {@code language}).
 */
public final class EffectiveSettings {

    private final Double cfgScale;
    private final Double temperature;
    private final Double speed;
    private final Integer maxNewTokens;
    private final String language;
    private final Boolean normalize;

    EffectiveSettings(Double cfgScale, Double temperature, Double speed,
                      Integer maxNewTokens, String language, Boolean normalize) {
        this.cfgScale = cfgScale;
        this.temperature = temperature;
        this.speed = speed;
        this.maxNewTokens = maxNewTokens;
        this.language = language;
        this.normalize = normalize;
    }

    public Double getCfgScale() { return cfgScale; }
    public Double getTemperature() { return temperature; }
    public Double getSpeed() { return speed; }
    public Integer getMaxNewTokens() { return maxNewTokens; }
    public String getLanguage() { return language; }
    public Boolean getNormalize() { return normalize; }

    /** Parse the server's {@code settings} echo object (snake_case fields). */
    static EffectiveSettings fromJson(JsonNode node) {
        if (node == null || node.isNull()) {
            return new EffectiveSettings(null, null, null, null, null, null);
        }
        return new EffectiveSettings(
                node.hasNonNull("cfg_scale") ? node.get("cfg_scale").asDouble() : null,
                node.hasNonNull("temperature") ? node.get("temperature").asDouble() : null,
                node.hasNonNull("speed") ? node.get("speed").asDouble() : null,
                node.hasNonNull("max_new_tokens") ? node.get("max_new_tokens").asInt() : null,
                node.hasNonNull("language") ? node.get("language").asText() : null,
                node.hasNonNull("normalize") ? node.get("normalize").asBoolean() : null
        );
    }

    @Override
    public String toString() {
        return "EffectiveSettings{cfgScale=" + cfgScale + ", temperature=" + temperature
                + ", speed=" + speed + ", maxNewTokens=" + maxNewTokens
                + ", language=" + language + ", normalize=" + normalize + '}';
    }
}
