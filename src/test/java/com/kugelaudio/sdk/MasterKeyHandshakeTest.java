package com.kugelaudio.sdk;

import com.kugelaudio.sdk.internal.Diagnostics;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/** A master key reaches the server as an Authorization header on every TTS socket, never in the URL. */
class MasterKeyHandshakeTest {
    private static final String KEY = "master-secret-0042";

    /** Records each upgrade request head over a real socket and refuses it with 401. */
    private static final class Recorder implements AutoCloseable {
        final ServerSocket server = new ServerSocket(0, 8, InetAddress.getLoopbackAddress());
        final List<String> heads = new CopyOnWriteArrayList<>();

        Recorder() throws Exception {
            Thread thread = new Thread(() -> {
                while (!server.isClosed()) {
                    try (Socket socket = server.accept()) {
                        heads.add(readHead(socket.getInputStream()));
                        socket.getOutputStream().write(
                                "HTTP/1.1 401 Unauthorized\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                                        .getBytes(StandardCharsets.US_ASCII));
                    } catch (Exception closed) {
                        return; // the server socket was closed by the test
                    }
                }
            });
            thread.setDaemon(true);
            thread.start();
        }

        KugelAudioOptions options() {
            return KugelAudioOptions.builder(KEY).masterKey()
                    .apiUrl("http://localhost:" + server.getLocalPort())
                    .autoConnect(false).timeout(Duration.ofSeconds(2)).build();
        }

        public void close() throws Exception { server.close(); }
    }

    private static String readHead(InputStream in) throws Exception {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            head.write(b);
            if (head.toString(StandardCharsets.US_ASCII).endsWith("\r\n\r\n")) break;
        }
        return head.toString(StandardCharsets.US_ASCII);
    }

    private static void assertHeaderOnly(String head, String path) {
        String requestLine = head.substring(0, head.indexOf("\r\n"));
        assertTrue(requestLine.contains(path), requestLine);
        assertFalse(requestLine.contains(KEY), "the master key is in the URL: " + requestLine);
        assertTrue(head.toLowerCase(Locale.ROOT).contains("\r\nauthorization: bearer " + KEY.toLowerCase(Locale.ROOT) + "\r\n"),
                "no Authorization header on the upgrade:\n" + head);
    }

    @Test
    void everyTtsSocketSendsTheMasterKeyAsAHeader() throws Exception {
        try (Recorder server = new Recorder()) {
            HttpClient http = HttpClient.newHttpClient();
            TTSResource tts = new TTSResource(server.options(), http, Diagnostics.disabled());
            assertThrows(RuntimeException.class, tts::connect);
            tts.close();
            StreamingSession session = new StreamingSession(server.options(), http,
                    StreamConfig.builder().build(), new StreamCallbacks() { public void onChunk(AudioChunk chunk) {} },
                    Diagnostics.disabled());
            assertThrows(RuntimeException.class, session::connect);
            MultiContextSession multi = new MultiContextSession(server.options(), http,
                    MultiContextConfig.builder().build(), Diagnostics.disabled());
            assertThrows(RuntimeException.class,
                    () -> multi.connect(new MultiContextCallbacks() { public void onChunk(String id, AudioChunk chunk) {} }));

            assertEquals(3, server.heads.size(), String.join("\n---\n", server.heads));
            assertHeaderOnly(server.heads.get(0), "/ws/tts");
            assertHeaderOnly(server.heads.get(1), "/ws/tts/stream");
            assertHeaderOnly(server.heads.get(2), "/ws/tts/multi");
        }
    }
}
