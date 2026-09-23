package com.kugelaudio.sdk;

import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * An in-memory {@link WebSocket} that records every text frame the SDK sends
 * and lets a test react to it via {@link #onSend}. No network.
 */
final class FakeWebSocket implements WebSocket {
    final List<String> sent = new CopyOnWriteArrayList<>();
    volatile Consumer<String> onSend = payload -> {};

    @Override
    public CompletableFuture<WebSocket> sendText(CharSequence data, boolean last) {
        String payload = data.toString();
        sent.add(payload);
        onSend.accept(payload);
        return CompletableFuture.completedFuture(this);
    }

    @Override
    public CompletableFuture<WebSocket> sendBinary(ByteBuffer data, boolean last) {
        return CompletableFuture.completedFuture(this);
    }

    @Override
    public CompletableFuture<WebSocket> sendPing(ByteBuffer message) {
        return CompletableFuture.completedFuture(this);
    }

    @Override
    public CompletableFuture<WebSocket> sendPong(ByteBuffer message) {
        return CompletableFuture.completedFuture(this);
    }

    @Override
    public CompletableFuture<WebSocket> sendClose(int statusCode, String reason) {
        return CompletableFuture.completedFuture(this);
    }

    @Override public void request(long n) {}
    @Override public String getSubprotocol() { return ""; }
    @Override public boolean isOutputClosed() { return false; }
    @Override public boolean isInputClosed() { return false; }
    @Override public void abort() {}
}
