package com.kugelaudio.sdk;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ModelTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

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
