package com.kugelaudio.sdk;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TranscriptionResponseTest {
    @Test
    void parsesOpenAiTextAndSpellingAlternatives() throws Exception {
        String json = """
                {"text":"Müller","transcript":"Müller","language":"de",
                 "duration_s":1.5,"model":"luchs-1",
                 "model_revision":"7278e1e70fe206f11671096ffdd38061171dd6e5","word_alternatives":[
                   {"raw_word_index":0,"word":"Müller","alternatives":[
                     {"spelling":"Mueller","probability":0.03}]}]}
                """;

        TranscriptionResponse result = new ObjectMapper().readValue(
                json, TranscriptionResponse.class);

        assertEquals("Müller", result.getText());
        assertEquals("luchs-1", result.getModel());
        assertEquals("7278e1e70fe206f11671096ffdd38061171dd6e5", result.getModelRevision());
        assertEquals("Mueller", result.getWordAlternatives().get(0)
                .alternatives().get(0).spelling());
    }
}
