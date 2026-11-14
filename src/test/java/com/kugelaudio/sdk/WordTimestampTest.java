package com.kugelaudio.sdk;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WordTimestampTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void deserializeFromJson() throws Exception {
        String json = """
                {
                    "word": "Hello",
                    "start_ms": 0,
                    "end_ms": 300,
                    "char_start": 0,
                    "char_end": 5,
                    "score": 0.95
                }
                """;
        WordTimestamp wt = MAPPER.readValue(json, WordTimestamp.class);
        assertEquals("Hello", wt.getWord());
        assertEquals(0, wt.getStartMs());
        assertEquals(300, wt.getEndMs());
        assertEquals(0, wt.getCharStart());
        assertEquals(5, wt.getCharEnd());
        assertEquals(0.95, wt.getScore(), 0.001);
    }

    @Test
    void deserializeOriginalOffsets() throws Exception {
        String json = """
                {"word": "twelve", "start_ms": 0, "end_ms": 300, "char_start": 6, "char_end": 12,
                 "original_char_start": 6, "original_char_end": 12, "score": 1.0}
                """;
        WordTimestamp wt = MAPPER.readValue(json, WordTimestamp.class);
        assertEquals(6, wt.getOriginalCharStart());
        assertEquals(12, wt.getOriginalCharEnd());
        assertNull(wt.getText());
    }

    @Test
    void olderServerLeavesNewFieldsNull() throws Exception {
        WordTimestamp wt = MAPPER.readValue(
                "{\"word\": \"Hi\", \"start_ms\": 0, \"end_ms\": 50, \"char_start\": 0, \"char_end\": 2}",
                WordTimestamp.class);
        assertNull(wt.getOriginalCharStart());
        assertNull(wt.getOriginalCharEnd());
        assertNull(wt.getText());
        assertNull(wt.getOriginalText());
    }

    @Test
    void frameParsingAttachesTextAndOriginalText() throws Exception {
        String frame = """
                {"word_timestamps": [
                   {"word": "twelve", "start_ms": 0, "end_ms": 300, "char_start": 6, "char_end": 12,
                    "original_char_start": 6, "original_char_end": 12, "score": 1.0}],
                 "chunk_id": 0,
                 "text": "About twelve thousand visitors.",
                 "original_text": "About 12,000 visitors."}
                """;
        WordTimestamp wt = WordTimestamp.listFromFrame(MAPPER.readTree(frame)).get(0);
        assertEquals("About twelve thousand visitors.", wt.getText());
        assertEquals("About 12,000 visitors.", wt.getOriginalText());
        assertEquals("12,000", wt.getOriginalText().substring(wt.getOriginalCharStart(), wt.getOriginalCharEnd()));
    }

    @Test
    void offsetsAreCodePointsAfterAnEmoji() throws Exception {
        String frame = """
                {"word_timestamps": [
                   {"word": "twelve", "start_ms": 0, "end_ms": 300, "char_start": 2, "char_end": 8,
                    "original_char_start": 2, "original_char_end": 8}],
                 "text": "\uD83D\uDE00 twelve visitors.",
                 "original_text": "\uD83D\uDE00 12,000 visitors."}
                """;
        WordTimestamp wt = WordTimestamp.listFromFrame(MAPPER.readTree(frame)).get(0);
        String original = wt.getOriginalText();
        int from = original.offsetByCodePoints(0, wt.getOriginalCharStart());
        int to = original.offsetByCodePoints(0, wt.getOriginalCharEnd());
        assertEquals("12,000", original.substring(from, to));
    }
}
