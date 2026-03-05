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
    void toStringFormat() {
        WordTimestamp wt = new WordTimestamp("world", 350, 700, 6, 11, 0.9);
        String str = wt.toString();
        assertTrue(str.contains("world"));
        assertTrue(str.contains("350"));
        assertTrue(str.contains("700"));
    }
}
