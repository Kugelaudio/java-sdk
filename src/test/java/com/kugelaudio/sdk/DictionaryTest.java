package com.kugelaudio.sdk;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DictionaryTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void dictionaryDeserializesSnakeCaseFields() throws Exception {
        String json = """
                {
                    "id": 7,
                    "project_id": 42,
                    "name": "Brand names",
                    "description": null,
                    "language": "en",
                    "is_active": true,
                    "created_at": "2026-01-01T00:00:00+00:00",
                    "updated_at": "2026-01-02T00:00:00+00:00"
                }
                """;
        Dictionary d = MAPPER.readValue(json, Dictionary.class);
        assertEquals(7, d.getId());
        assertEquals(42, d.getProjectId());
        assertEquals("Brand names", d.getName());
        assertNull(d.getDescription());
        assertEquals("en", d.getLanguage());
        assertTrue(d.isActive());
    }

    @Test
    void entryListResponseDeserializes() throws Exception {
        String json = """
                {
                    "entries": [
                        {
                            "id": 1,
                            "dictionary_id": 7,
                            "word": "x",
                            "replacement": "y",
                            "ipa": null,
                            "case_sensitive": false,
                            "created_at": "2026-01-01T00:00:00+00:00",
                            "updated_at": "2026-01-01T00:00:00+00:00"
                        }
                    ],
                    "total": 12,
                    "limit": 25,
                    "offset": 0
                }
                """;
        DictionaryEntryListResponse r = MAPPER.readValue(json, DictionaryEntryListResponse.class);
        assertEquals(1, r.getEntries().size());
        assertEquals(12, r.getTotal());
        assertEquals(25, r.getLimit());
        assertEquals("x", r.getEntries().get(0).getWord());
    }

    @Test
    void dictionaryIgnoresUnknownFields() throws Exception {
        String json = """
                {"id": 1, "project_id": 2, "name": "x", "description": null,
                 "language": null, "is_active": true,
                 "created_at": "", "updated_at": "",
                 "future_field": "ok"}
                """;
        Dictionary d = MAPPER.readValue(json, Dictionary.class);
        assertEquals(1, d.getId());
    }

    @Test
    void entryInputSerializesWithSnakeCase() {
        DictionaryEntryInput input = new DictionaryEntryInput(
                "Postgres", "post-gres", "ˈpoʊstɡrɛs", true);
        Map<String, Object> map = input.toMap();
        assertEquals("Postgres", map.get("word"));
        assertEquals("post-gres", map.get("replacement"));
        assertEquals("ˈpoʊstɡrɛs", map.get("ipa"));
        assertEquals(true, map.get("case_sensitive"));
        assertFalse(map.containsKey("caseSensitive"));
    }

    @Test
    void entryInputOmitsOptionalFieldsWhenNull() {
        DictionaryEntryInput input = new DictionaryEntryInput("x", "y");
        Map<String, Object> map = input.toMap();
        assertEquals("x", map.get("word"));
        assertEquals("y", map.get("replacement"));
        assertFalse(map.containsKey("ipa"));
        assertFalse(map.containsKey("case_sensitive"));
    }

    @Test
    void entryInputRejectsEmptyWord() {
        assertThrows(ValidationException.class,
                () -> new DictionaryEntryInput("", "y"));
    }

}
