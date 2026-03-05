package com.kugelaudio.sdk;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Per-word timing data returned by the TTS engine when word timestamps are enabled.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class WordTimestamp {

    private final String word;
    private final long startMs;
    private final long endMs;
    private final int charStart;
    private final int charEnd;
    private final double score;

    public WordTimestamp(
            @JsonProperty("word") String word,
            @JsonProperty("start_ms") long startMs,
            @JsonProperty("end_ms") long endMs,
            @JsonProperty("char_start") int charStart,
            @JsonProperty("char_end") int charEnd,
            @JsonProperty("score") double score) {
        this.word = word;
        this.startMs = startMs;
        this.endMs = endMs;
        this.charStart = charStart;
        this.charEnd = charEnd;
        this.score = score;
    }

    public String getWord() { return word; }
    public long getStartMs() { return startMs; }
    public long getEndMs() { return endMs; }
    public int getCharStart() { return charStart; }
    public int getCharEnd() { return charEnd; }
    public double getScore() { return score; }

    @Override
    public String toString() {
        return "WordTimestamp{word='" + word + "', start=" + startMs + "ms, end=" + endMs + "ms}";
    }
}
