package com.kugelaudio.sdk;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Request bodies over plain {@code http://} must survive a uvicorn-style
 * server. Java's {@code HttpClient} defaults to HTTP/2 and, without TLS,
 * offers an h2c upgrade ({@code Connection: Upgrade, HTTP2-Settings},
 * {@code Upgrade: h2c}) on every request; uvicorn does not speak h2c and drops
 * the body. The SDK pins HTTP/1.1, like the Python and JS SDKs.
 */
class PlainHttpBodyTest {

    /**
     * Reads a request body the way uvicorn effectively does: a request that
     * asks for a non-WebSocket protocol upgrade (h2c) arrives with no body.
     */
    static byte[] bodyAsUvicornSeesIt(HttpExchange exchange) throws IOException {
        byte[] body = exchange.getRequestBody().readAllBytes();
        String upgrade = exchange.getRequestHeaders().getFirst("Upgrade");
        boolean h2cUpgrade = upgrade != null && !"websocket".equalsIgnoreCase(upgrade.trim());
        return h2cUpgrade ? new byte[0] : body;
    }

    record Received(String upgrade, String body) {}

    @Test
    void mainClientPostBodyArrivesIntactWithoutAnUpgradeOffer() throws Exception {
        CompletableFuture<Received> received = new CompletableFuture<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/v1/dictionaries", exchange -> {
            try (exchange) {
                byte[] body = bodyAsUvicornSeesIt(exchange);
                received.complete(new Received(
                        exchange.getRequestHeaders().getFirst("Upgrade"),
                        new String(body, StandardCharsets.UTF_8)));
                exchange.sendResponseHeaders(401, -1);
            }
        });
        server.start();
        try (KugelAudio client = new KugelAudio(KugelAudioOptions.builder("test-key")
                .apiUrl("http://127.0.0.1:" + server.getAddress().getPort())
                .autoConnect(false)
                .telemetry(false)
                .build())) {
            assertThrows(KugelAudioException.class,
                    () -> client.dictionaries().create("PlainHttpDictionary", "desc", "de"));

            Received request = received.get(5, TimeUnit.SECONDS);
            assertNull(request.upgrade(), "no h2c upgrade offer on plain http");
            assertTrue(request.body().contains("PlainHttpDictionary"),
                    "the JSON body must reach the server, got: '" + request.body() + "'");
        } finally {
            server.stop(0);
        }
    }
}
