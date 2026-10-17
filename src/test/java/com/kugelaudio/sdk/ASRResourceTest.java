package com.kugelaudio.sdk;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ASRResourceTest {

    private static final byte[] AUDIO = "RIFFdata".getBytes(StandardCharsets.UTF_8);

    private static String upload(List<String> phrases) throws IOException {
        return new String(
                ASRResource.encodeUpload("b", AUDIO, "audio.wav", "audio/wav", null, "test-model", phrases,
                        null, null),
                StandardCharsets.UTF_8);
    }

    private static String upload(Long projectId, List<Integer> dictionaryIds) throws IOException {
        return new String(
                ASRResource.encodeUpload("b", AUDIO, "audio.wav", "audio/wav", null, "test-model", List.of(),
                        projectId, dictionaryIds),
                StandardCharsets.UTF_8);
    }

    /** The multipart body {@code call} sends, read by a loopback server that answers 200. */
    private static String bodySentBy(Function<KugelAudio, TranscriptionResponse> call) throws Exception {
        CompletableFuture<String> received = new CompletableFuture<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/v1/audio/transcriptions", exchange -> {
            try (exchange) {
                received.complete(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                byte[] response = """
                        {"text":"hi","transcript":"hi","language":"en","duration_s":1.0,
                         "model":"test-model","word_alternatives":[]}
                        """.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            }
        });
        server.start();
        try (KugelAudio client = new KugelAudio(KugelAudioOptions.builder("test-key")
                .apiUrl("http://127.0.0.1:" + server.getAddress().getPort())
                .autoConnect(false)
                .telemetry(false)
                .build())) {
            assertEquals("test-model", call.apply(client).getModel());
            return received.get(5, TimeUnit.SECONDS);
        } finally {
            server.stop(0);
        }
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
    void uploadCarriesProjectAndOneFormFieldPerDictionaryId() throws IOException {
        String body = upload(42L, List.of(7, 9));

        assertTrue(body.contains("name=\"project_id\"\r\nContent-Type: text/plain\r\n\r\n42\r\n"), body);
        assertEquals(3, body.split("name=\"dictionary_ids\"", -1).length);
        assertTrue(body.contains("name=\"dictionary_ids\"\r\nContent-Type: text/plain\r\n\r\n7\r\n"), body);
        assertTrue(body.contains("name=\"dictionary_ids\"\r\nContent-Type: text/plain\r\n\r\n9\r\n"), body);
    }

    @Test
    void uploadWithoutSelectionHasNoDictionaryFields() throws IOException {
        String body = upload(null, null);

        assertFalse(body.contains("project_id"), body);
        assertFalse(body.contains("dictionary_ids"), body);
    }

    @Test
    void emptyDictionarySelectionSendsOneEmptyField() throws Exception {
        // Repeated form fields cannot carry []; one empty dictionary_ids field
        // is the multipart spelling of "no dictionaries". Dropping it would
        // apply every active dictionary instead.
        String body = bodySentBy(client -> client.asr().transcribe(
                AUDIO, "audio.wav", "audio/wav", null, "test-model", List.of(), null, List.of()));

        assertEquals(2, body.split("name=\"dictionary_ids\"", -1).length, body);
        assertTrue(body.contains("name=\"dictionary_ids\"\r\nContent-Type: text/plain\r\n\r\n\r\n"), body);
    }

    @Test
    void transcribeWithoutAModelLeavesTheFieldOut() throws Exception {
        String body = bodySentBy(client -> client.asr().transcribe(AUDIO));

        assertTrue(body.contains("name=\"file\"; filename=\"audio.wav\""), body);
        assertFalse(body.contains("name=\"model\""), body);
    }

    @Test
    void transcribeSendsAGivenModelVerbatim() throws Exception {
        String body = bodySentBy(client -> client.asr().transcribe(
                AUDIO, "audio.wav", "audio/wav", null, "test-model"));

        assertTrue(body.contains("name=\"model\"\r\nContent-Type: text/plain\r\n\r\ntest-model\r\n"), body);
    }

    @Test
    void nullPhraseListIsRejectedBeforeNetwork() {
        KugelAudio client = new KugelAudio(
                KugelAudioOptions.builder("test-key").build());

        assertThrows(ValidationException.class, () -> client.asr().transcribe(
                AUDIO, "audio.wav", "audio/wav", null, "test-model", null));
    }
}
