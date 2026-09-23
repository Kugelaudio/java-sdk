package com.kugelaudio.sdk;

import com.kugelaudio.sdk.internal.HttpHelper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Wire-shape tests for {@link VoicesResource} against a local HTTP stub. */
class VoicesResourceTest {

    private HttpServer server;
    private VoicesResource voices;
    private final AtomicReference<String> requestPath = new AtomicReference<>();
    private final AtomicReference<String> requestBody = new AtomicReference<>();

    private void start(String responseJson) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requestPath.set(exchange.getRequestURI().getPath());
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] out = responseJson.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, out.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(out);
            }
        });
        server.start();
        KugelAudioOptions options = KugelAudioOptions.builder("test-key")
                .apiUrl("http://127.0.0.1:" + server.getAddress().getPort())
                .autoConnect(false)
                .build();
        voices = new VoicesResource(new HttpHelper(HttpClient.newHttpClient(), options));
    }

    @BeforeEach
    void reset() {
        requestPath.set(null);
        requestBody.set(null);
    }

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    @Test
    void generateSampleReturnsSampleUrl() throws IOException {
        start("{\"sample_s3_path\": \"voices/7/sample.wav\", \"sample_url\": \"https://cdn.example/sample.wav\"}");

        VoiceDetail sample = voices.generateSample(7);

        assertEquals("/v1/voices/7/generate-sample", requestPath.get());
        assertEquals("https://cdn.example/sample.wav", sample.getSampleUrl());
    }

    @Test
    void createSendsLanguageAsSupportedLanguages(@TempDir Path tmp) throws IOException {
        start("{\"id\": 1, \"voice_id\": 1, \"name\": \"Anna \\\"A\\\"\"}");
        Path wav = Files.write(tmp.resolve("ref.wav"), new byte[] {1, 2, 3});

        voices.create("Anna \"A\"", "female", "de", List.of(wav));

        String body = requestBody.get();
        assertEquals("/v1/voices", requestPath.get());
        assertTrue(body.contains("{\"name\":\"Anna \\\"A\\\"\",\"sex\":\"female\",\"supported_languages\":[\"de\"]}"),
                "metadata part was: " + body);
        assertFalse(body.contains("\"language\""), "legacy 'language' key must not be sent: " + body);
        assertTrue(body.contains("filename=\"ref.wav\""));
    }

    @Test
    void createWithoutLanguageOmitsSupportedLanguages() throws IOException {
        start("{\"id\": 1, \"voice_id\": 1, \"name\": \"Anna\"}");

        voices.create("Anna", "female", null, List.of());

        String body = requestBody.get();
        assertTrue(body.contains("{\"name\":\"Anna\",\"sex\":\"female\"}"), "metadata part was: " + body);
        assertFalse(body.contains("supported_languages"));
    }
}
