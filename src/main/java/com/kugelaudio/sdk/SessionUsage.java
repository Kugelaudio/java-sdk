package com.kugelaudio.sdk;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Per-session usage reported in the {@code session_closed} frame (KUG-1192).
 *
 * <p>Lets you bill your own customers per conversation. {@link #getCostCents()}
 * is the actual amount charged in <b>EUR cents</b>. When the charge could not
 * be determined at session end (e.g. a transient billing error) it is
 * {@code null} and {@link #isCostAvailable()} is {@code false} — never a
 * misleading {@code 0}. {@link #getAudioSeconds()} is always reported. On
 * {@code /ws/tts/multi} usage is reported per context (per conversation) on
 * each {@code context_closed} frame, not aggregated across contexts.
 */
public final class SessionUsage {

    private final double audioSeconds;
    private final Double costCents;
    private final String currency;
    private final Integer characters;
    private final String modelId;

    public SessionUsage(double audioSeconds, Double costCents, String currency,
                        Integer characters, String modelId) {
        this.audioSeconds = audioSeconds;
        this.costCents = costCents;
        this.currency = currency;
        this.characters = characters;
        this.modelId = modelId;
    }

    /**
     * Parse a {@code session_closed} payload into typed usage.
     *
     * <p>Reads the nested {@code usage} object when present, otherwise falls
     * back to the top-level {@code total_audio_seconds} for older servers.
     * Returns {@code null} when no usage information is present.
     */
    static SessionUsage fromSessionClosed(JsonNode json) {
        JsonNode usage = json.get("usage");
        JsonNode source = (usage != null && usage.isObject()) ? usage : json;

        double audioSeconds;
        if (source.hasNonNull("audio_seconds")) {
            audioSeconds = source.path("audio_seconds").asDouble();
        } else if (json.hasNonNull("total_audio_seconds")) {
            audioSeconds = json.path("total_audio_seconds").asDouble();
        } else {
            return null;
        }

        Double costCents = source.hasNonNull("cost_cents")
                ? source.path("cost_cents").asDouble() : null;
        String currency = source.hasNonNull("currency")
                ? source.path("currency").asText() : null;
        Integer characters = source.hasNonNull("characters")
                ? source.path("characters").asInt() : null;
        String modelId = source.hasNonNull("model_id")
                ? source.path("model_id").asText() : null;

        return new SessionUsage(audioSeconds, costCents, currency, characters, modelId);
    }

    /** Total audio generated this session, in seconds (the unit we bill on). */
    public double getAudioSeconds() { return audioSeconds; }

    /** Actual amount charged in EUR cents, or {@code null} if undetermined. */
    public Double getCostCents() { return costCents; }

    /** Currency of {@link #getCostCents()} ({@code "eur"}), or {@code null}. */
    public String getCurrency() { return currency; }

    /** Total input characters submitted this session, or {@code null}. */
    public Integer getCharacters() { return characters; }

    /** Model that produced the audio, or {@code null}. */
    public String getModelId() { return modelId; }

    /** {@code true} when an authoritative charge was returned for this session. */
    public boolean isCostAvailable() { return costCents != null; }

    @Override
    public String toString() {
        return "SessionUsage{audioSeconds=" + audioSeconds
                + ", costCents=" + costCents
                + ", currency='" + currency + "'"
                + ", characters=" + characters
                + ", modelId='" + modelId + "'}";
    }
}
