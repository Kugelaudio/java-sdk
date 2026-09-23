package com.kugelaudio.sdk;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ASRResourceTest {

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
