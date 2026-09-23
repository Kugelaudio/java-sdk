package com.kugelaudio.sdk.internal;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Owns opening handshakes until publication, including timeout/close races.
 *
 * <p>Every handshake carries the {@link SdkMetadata#sdkHeaders() SDK identity
 * headers}, so ingress observability sees the same identity on WebSocket
 * upgrades as it does on HTTP requests.
 */
public final class WsConnector {
    private final Set<CompletableFuture<WebSocket>> pending = new HashSet<>();
    private boolean closed;
    private WebSocket current;

    public WebSocket connect(HttpClient client, URI uri, WebSocket.Listener listener, Duration timeout)
            throws InterruptedException, ExecutionException, TimeoutException {
        long deadline = System.nanoTime() + timeout.toNanos();
        CompletableFuture<WebSocket> future;
        AtomicBoolean abandoned = new AtomicBoolean();
        synchronized (this) {
            if (closed) throw new IllegalStateException("WebSocket owner is closed");
            WebSocket.Builder builder = client.newWebSocketBuilder().connectTimeout(timeout);
            SdkMetadata.sdkHeaders().forEach(builder::header);
            future = builder.buildAsync(uri, listener);
            pending.add(future);
        }
        future.thenAccept(socket -> {
            synchronized (this) {
                if (closed || abandoned.get()) socket.abort();
            }
        });
        try {
            WebSocket socket = future.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            synchronized (this) {
                if (closed) {
                    socket.abort();
                    throw new IllegalStateException("WebSocket owner closed during connect");
                }
                current = socket;
                return socket;
            }
        } catch (InterruptedException | ExecutionException | TimeoutException | RuntimeException error) {
            abandoned.set(true);
            if (!future.cancel(true) && !future.isCompletedExceptionally()) {
                WebSocket socket = future.getNow(null);
                if (socket != null) socket.abort();
            }
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            throw error;
        } finally {
            synchronized (this) { pending.remove(future); }
        }
    }

    /** Cancel pending work; return the published socket for the owner's graceful close. */
    public synchronized WebSocket close() {
        closed = true;
        for (CompletableFuture<WebSocket> future : pending) future.cancel(true);
        return current;
    }
}
