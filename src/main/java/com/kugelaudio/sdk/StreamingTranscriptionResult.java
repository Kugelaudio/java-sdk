package com.kugelaudio.sdk;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** Replaceable rolling hypothesis received from the public ASR WebSocket. */
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
        List<TranscriptionResponse.WordAlternatives> wordAlternatives) {
    public StreamingTranscriptionResult(
            String type,
            String partialText,
            boolean isFinal,
            String turnEndReason,
            Double turnEndConfidence,
            Double turnEndInferenceMs,
            List<TranscriptionResponse.WordAlternatives> wordAlternatives) {
        this(type, partialText, isFinal, null, null, turnEndReason,
                turnEndConfidence, turnEndInferenceMs, wordAlternatives);
    }
}
