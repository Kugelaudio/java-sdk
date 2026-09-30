package com.kugelaudio.sdk;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ASRResourceTest {

    private static String upload(List<String> phrases) throws IOException {
        return new String(
                ASRResource.encodeUpload(
                        "b", "RIFFdata".getBytes(), "audio.wav", "audio/wav", null, ASRResource.MODEL_ID, phrases),
                StandardCharsets.UTF_8);
    }

    @Test
    void uploadCarriesOneFormFieldPerBoostedPhrase() throws IOException {
        String body = upload(List.of("Kargo", "Acme, Inc."));

        assertEquals(3, body.split("name=\"boosted_phrases\"", -1).length);
        assertTrue(body.contains("name=\"boosted_phrases\"\r\nContent-Type: text/plain\r\n\r\nKargo\r\n"));
        assertTrue(body.contains("\r\n\r\nAcme, Inc.\r\n"));
    }

    @Test
    void uploadWithoutPhrasesHasNoPhraseField() throws IOException {
        assertFalse(upload(List.of()).contains("boosted_phrases"));
    }

    @Test
    void nullPhraseListIsRejectedBeforeNetwork() {
        KugelAudio client = new KugelAudio(
                KugelAudioOptions.builder("test-key").build());

        assertThrows(ValidationException.class, () -> client.asr().transcribe(
                "RIFFdata".getBytes(), "audio.wav", "audio/wav", null, ASRResource.MODEL_ID, null));
    }

    @Test
    void transcribeRejectsUnknownModelBeforeNetwork() {
        KugelAudio client = new KugelAudio(
                KugelAudioOptions.builder("test-key").build());

        assertThrows(ValidationException.class, () -> client.asr().transcribe(
                "RIFFdata".getBytes(),
                "audio.wav",
                "audio/wav",
                null,
                "qwen3-asr"));
    }
}
