package com.kugelaudio.sdk;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Per-word timing data returned by the TTS engine when word timestamps are enabled.
 *
 * <p>{@code charStart}/{@code charEnd} index {@link #getText()} (what was spoken, after
 * normalization). {@code originalCharStart}/{@code originalCharEnd} index
 * {@link #getOriginalText()} (your text). All offsets count Unicode code points, not UTF-16
 * units: use {@code String.offsetByCodePoints} to slice. The four fields added for original
 * offsets are {@code null} when the server does not send them.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class WordTimestamp {

    private final String word;
    private final long startMs;
    private final long endMs;
    private final int charStart;
    private final int charEnd;
    private final double score;
    private final Integer originalCharStart;
    private final Integer originalCharEnd;
    private final String text;
    private final String originalText;

    public WordTimestamp(String word, long startMs, long endMs, int charStart, int charEnd, double score) {
        this(word, startMs, endMs, charStart, charEnd, score, null, null, null, null);
    }

    @JsonCreator
    public WordTimestamp(
            @JsonProperty("word") String word,
            @JsonProperty("start_ms") long startMs,
            @JsonProperty("end_ms") long endMs,
            @JsonProperty("char_start") int charStart,
            @JsonProperty("char_end") int charEnd,
            @JsonProperty("score") double score,
            @JsonProperty("original_char_start") Integer originalCharStart,
            @JsonProperty("original_char_end") Integer originalCharEnd,
            @JsonProperty("text") String text,
            @JsonProperty("original_text") String originalText) {
        this.word = word;
        this.startMs = startMs;
        this.endMs = endMs;
        this.charStart = charStart;
        this.charEnd = charEnd;
        this.score = score;
        this.originalCharStart = originalCharStart;
        this.originalCharEnd = originalCharEnd;
        this.text = text;
        this.originalText = originalText;
    }

    /**
     * Parses a {@code word_timestamps} frame; every word also carries the frame's
     * {@code text} and {@code original_text}.
     */
    static List<WordTimestamp> listFromFrame(JsonNode frame) {
        String text = frame.hasNonNull("text") ? frame.get("text").asText() : null;
        String originalText = frame.hasNonNull("original_text") ? frame.get("original_text").asText() : null;
        List<WordTimestamp> out = new ArrayList<>();
        for (JsonNode wt : frame.get("word_timestamps")) {
            out.add(new WordTimestamp(
                    wt.path("word").asText(),
                    wt.path("start_ms").asLong(),
                    wt.path("end_ms").asLong(),
                    wt.path("char_start").asInt(),
                    wt.path("char_end").asInt(),
                    wt.path("score").asDouble(),
                    wt.hasNonNull("original_char_start") ? wt.get("original_char_start").asInt() : null,
                    wt.hasNonNull("original_char_end") ? wt.get("original_char_end").asInt() : null,
                    text,
                    originalText));
        }
        return out;
    }

    public String getWord() { return word; }
    public long getStartMs() { return startMs; }
    public long getEndMs() { return endMs; }
    /** Start offset of the word in {@link #getText()}. */
    public int getCharStart() { return charStart; }
    /** Exclusive end offset of the word in {@link #getText()}. */
    public int getCharEnd() { return charEnd; }
    public double getScore() { return score; }
    /** Start offset in {@link #getOriginalText()} of the words this word speaks, or {@code null}. */
    public Integer getOriginalCharStart() { return originalCharStart; }
    /** Exclusive end offset in {@link #getOriginalText()}, or {@code null}. */
    public Integer getOriginalCharEnd() { return originalCharEnd; }
    /** The text the chunk spoke, after normalization (the frame's {@code text}), or {@code null}. */
    public String getText() { return text; }
    /** The part of your text the chunk spoke, before normalization, or {@code null}. */
    public String getOriginalText() { return originalText; }

    @Override
    public String toString() {
        return "WordTimestamp{word='" + word + "', start=" + startMs + "ms, end=" + endMs + "ms}";
    }
}
