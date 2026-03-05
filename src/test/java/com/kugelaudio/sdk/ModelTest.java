package com.kugelaudio.sdk;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ModelTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void deserializeFromJson() throws Exception {
        String json = """
                {"id": "kugel-1-turbo", "name": "Kugel 1 Turbo", "description": "Fast TTS", "parameters": "1.5B", "max_input_length": 5000, "sample_rate": 24000}
                """;
        Model model = MAPPER.readValue(json, Model.class);
        assertEquals("kugel-1-turbo", model.getId());
        assertEquals("Kugel 1 Turbo", model.getName());
        assertEquals("1.5B", model.getParameters());
        assertEquals(5000, model.getMaxInputLength());
        assertEquals(24000, model.getSampleRate());
    }

    @Test
    void deserializeIgnoresUnknownFields() throws Exception {
        String json = """
                {"id": "kugel-1", "name": "Kugel 1", "parameters": null, "max_input_length": 0, "sample_rate": 0, "extra_field": true}
                """;
        Model model = MAPPER.readValue(json, Model.class);
        assertEquals("kugel-1", model.getId());
        assertNull(model.getParameters());
    }
}
