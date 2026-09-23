package com.kugelaudio.sdk;

import com.kugelaudio.sdk.internal.Diagnostics;
import org.junit.jupiter.api.Test;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class ConnectionLifecycleTest {
    /** Accept the HTTP upgrade but never answer it; record client teardown. */
    private static final class BlackHole implements AutoCloseable {
        final ServerSocket server = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress());
        final CompletableFuture<Socket> accepted = new CompletableFuture<>();
        final CompletableFuture<Boolean> disconnected = new CompletableFuture<>();
        BlackHole() throws Exception {
            Thread thread = new Thread(() -> {
                try {
                    Socket socket = server.accept();
                    accepted.complete(socket);
                    while (socket.getInputStream().read() != -1) { }
                    disconnected.complete(true);
                } catch (Exception error) {
                    disconnected.completeExceptionally(error);
                }
            });
            thread.setDaemon(true);
            thread.start();
        }
        KugelAudioOptions options() {
            return KugelAudioOptions.builder("local-test-key")
                    .apiUrl("http://localhost:" + server.getLocalPort())
                    .autoConnect(false).timeout(Duration.ofMillis(250)).build();
        }
        public void close() throws Exception {
            server.close();
            if (accepted.isDone()) accepted.get().close();
        }
    }

    @Test void streamingTimeoutAbortsTheHandshake() throws Exception {
        try (BlackHole server = new BlackHole()) {
            StreamingSession session = new StreamingSession(server.options(), HttpClient.newHttpClient(),
                    StreamConfig.builder().build(), new StreamCallbacks() { public void onChunk(AudioChunk chunk) {} },
                    Diagnostics.disabled());
            long started = System.nanoTime();
            assertThrows(ConnectionException.class, session::connect);
            long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            assertTrue(elapsed >= 150, "subsecond budget was truncated: " + elapsed);
            server.accepted.get(2, TimeUnit.SECONDS);
            assertTrue(server.disconnected.get(1, TimeUnit.SECONDS));
        }
    }

    @Test void multiCloseAbortsThePendingHandshake() throws Exception {
        try (BlackHole server = new BlackHole()) {
            MultiContextSession session = new MultiContextSession(server.options(), HttpClient.newHttpClient(),
                    MultiContextConfig.builder().build(), Diagnostics.disabled());
            CompletableFuture<Void> connecting = CompletableFuture.runAsync(() -> session.connect(new MultiContextCallbacks() { public void onChunk(String id, AudioChunk chunk) {} }));
            server.accepted.get(2, TimeUnit.SECONDS);
            session.close();
            assertThrows(ExecutionException.class, () -> connecting.get(1, TimeUnit.SECONDS));
            assertTrue(server.disconnected.get(1, TimeUnit.SECONDS));
        }
    }

    @Test void pooledLockWaitAndHandshakeShareOneBudget() throws Exception {
        try (BlackHole server = new BlackHole()) {
            TTSResource tts = new TTSResource(server.options(), HttpClient.newHttpClient(), Diagnostics.disabled());
            CompletableFuture<Void> first = CompletableFuture.runAsync(tts::connect);
            try {
                server.accepted.get(2, TimeUnit.SECONDS);
                long started = System.nanoTime();
                assertThrows(ConnectionException.class, tts::connect);
                long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
                assertTrue(elapsed < 400, "lock wait added another connect budget: " + elapsed);
            } finally {
                tts.close();
                assertThrows(ExecutionException.class, () -> first.get(2, TimeUnit.SECONDS));
            }
        }
    }
}
