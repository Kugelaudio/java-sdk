package com.kugelaudio.sdk;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class TranscriptionResponseTest {
    @Test
    void parsesOpenAiTextAndSpellingAlternatives() throws Exception {
        String json = """
                {"text":"Müller","transcript":"Müller","language":"de",
                 "duration_s":1.5,"model":"test-model",
                 "model_revision":"7278e1e70fe206f11671096ffdd38061171dd6e5","word_alternatives":[
                   {"raw_word_index":0,"word":"Müller","alternatives":[
                     {"spelling":"Mueller","probability":0.03}]}]}
                """;

        TranscriptionResponse result = new ObjectMapper().readValue(
                json, TranscriptionResponse.class);

        assertEquals("Müller", result.getText());
        assertEquals("test-model", result.getModel());
        assertEquals("7278e1e70fe206f11671096ffdd38061171dd6e5", result.getModelRevision());
        assertEquals("Mueller", result.getWordAlternatives().get(0)
                .alternatives().get(0).spelling());
    }

    @Test
    void streamingFrameExposesDetectedLanguageOnlyWhenSent() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        StreamingTranscriptionResult revision = mapper.readValue("""
                {"type":"alternatives","partial_text":"Ich möchte einen Flug buchen.",
                 "is_final":true,"revises_final":true,"language":"German",
                 "model":"test-model","word_alternatives":[]}
                """, StreamingTranscriptionResult.class);
        StreamingTranscriptionResult partial = mapper.readValue("""
                {"type":"partial","partial_text":"Ich möchte","is_final":false,
                 "word_alternatives":[]}
                """, StreamingTranscriptionResult.class);

        assertEquals("German", revision.language());
        assertNull(partial.language());
        assertNull(new StreamingTranscriptionResult(
                "partial", "x", false, null, null, null, List.of()).language());
    }
}
