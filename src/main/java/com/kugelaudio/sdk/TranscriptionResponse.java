package com.kugelaudio.sdk;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** Final speech-to-text response returned by KugelAudio. */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class TranscriptionResponse {
    private final String text;
    private final String transcript;
    private final String language;
    private final double durationSeconds;
    private final String model;
    private final String modelRevision;
    private final List<WordAlternatives> wordAlternatives;

    @JsonCreator
    public TranscriptionResponse(
            @JsonProperty("text") String text,
            @JsonProperty("transcript") String transcript,
            @JsonProperty("language") String language,
            @JsonProperty("duration_s") double durationSeconds,
            @JsonProperty("model") String model,
            @JsonProperty("model_revision") String modelRevision,
            @JsonProperty("word_alternatives") List<WordAlternatives> wordAlternatives) {
        this.text = text;
        this.transcript = transcript;
        this.language = language;
        this.durationSeconds = durationSeconds;
        this.model = model;
        this.modelRevision = modelRevision;
        this.wordAlternatives = wordAlternatives == null ? List.of() : List.copyOf(wordAlternatives);
    }

    public TranscriptionResponse(
            String text,
            String transcript,
            String language,
            double durationSeconds,
            String model,
            List<WordAlternatives> wordAlternatives) {
        this(text, transcript, language, durationSeconds, model, null, wordAlternatives);
    }

    public String getText() { return text; }
    public String getTranscript() { return transcript; }
    public String getLanguage() { return language; }
    public double getDurationSeconds() { return durationSeconds; }
    public String getModel() { return model; }
    public String getModelRevision() { return modelRevision; }
    public List<WordAlternatives> getWordAlternatives() { return wordAlternatives; }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record WordAlternatives(
            @JsonProperty("raw_word_index") int rawWordIndex,
            @JsonProperty("word") String word,
            @JsonProperty("alternatives") List<SpellingAlternative> alternatives) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SpellingAlternative(
            @JsonProperty("spelling") String spelling,
            @JsonProperty("probability") double probability) {}
}
