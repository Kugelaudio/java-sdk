package com.kugelaudio.sdk;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Replaceable rolling hypothesis received from the public ASR WebSocket.
 *
 * <p>{@code language} is the detected language (for example {@code "German"} or
 * {@code "English"}). It is set on the turn's revision frame and is {@code null}
 * on partials and on frames that do not report it.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record StreamingTranscriptionResult(
        @JsonProperty("type") String type,
        @JsonProperty("partial_text") String partialText,
        @JsonProperty("is_final") boolean isFinal,
        @JsonProperty("model") String model,
        @JsonProperty("model_revision") String modelRevision,
        @JsonProperty("turn_end_reason") String turnEndReason,
        @JsonProperty("turn_end_confidence") Double turnEndConfidence,
        @JsonProperty("turn_end_inference_ms") Double turnEndInferenceMs,
        @JsonProperty("word_alternatives")
        List<TranscriptionResponse.WordAlternatives> wordAlternatives,
        @JsonProperty("language") String language) {
    public StreamingTranscriptionResult(
            String type,
            String partialText,
            boolean isFinal,
            String model,
            String modelRevision,
            String turnEndReason,
            Double turnEndConfidence,
            Double turnEndInferenceMs,
            List<TranscriptionResponse.WordAlternatives> wordAlternatives) {
        this(type, partialText, isFinal, model, modelRevision, turnEndReason,
                turnEndConfidence, turnEndInferenceMs, wordAlternatives, null);
    }

    public StreamingTranscriptionResult(
            String type,
            String partialText,
            boolean isFinal,
            String turnEndReason,
            Double turnEndConfidence,
            Double turnEndInferenceMs,
            List<TranscriptionResponse.WordAlternatives> wordAlternatives) {
        this(type, partialText, isFinal, null, null, turnEndReason,
                turnEndConfidence, turnEndInferenceMs, wordAlternatives, null);
    }
}
