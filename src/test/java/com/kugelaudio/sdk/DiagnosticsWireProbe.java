package com.kugelaudio.sdk;

import java.time.Duration;

/**
 * Forked-JVM driver for {@link DiagnosticsWireTest}: a real client with the
 * real diagnostics sender, run in its own process so the environment (the
 * {@code KUGELAUDIO_TELEMETRY} override), DNS ({@code -Djdk.net.hosts.file})
 * and JVM exit can be controlled and observed.
 *
 * <p>Args: {@code <apiUrl> <unset|true|false> <httpCalls> [close|uncaught|idle]}.
 * {@code close} (default) fails some calls then closes the client;
 * {@code uncaught} lets the generate() failure escape main without closing;
 * {@code idle} returns from main without any call and without closing. Prints only
 * {@code PROBE }-prefixed marker lines; anything else on stdout/stderr is
 * output the SDK produced on its own.
 */
public final class DiagnosticsWireProbe {

    /** Input text that must never appear in a diagnostics payload. */
    static final String SECRET_TEXT = "ProbeSecretInputText";
    static final String API_KEY = "ka_probe_secret_key";

    private DiagnosticsWireProbe() {}

    public static void main(String[] args) {
        String apiUrl = args[0];
        String telemetry = args[1];
        int httpCalls = Integer.parseInt(args[2]);
        String mode = args.length > 3 ? args[3] : "close";

        KugelAudioOptions.Builder builder = KugelAudioOptions.builder(API_KEY)
                .apiUrl(apiUrl)
                .autoConnect(false)
                .timeout(Duration.ofSeconds(3));
        if (!"unset".equals(telemetry)) builder.telemetry(Boolean.parseBoolean(telemetry));
        KugelAudio client = new KugelAudio(builder.build());

        if ("idle".equals(mode)) {
            System.out.println("PROBE MAIN_DONE_AT=" + System.currentTimeMillis());
            return;
        }
        if ("uncaught".equals(mode)) {
            try {
                client.tts().generate(GenerateRequest.builder(SECRET_TEXT).voiceId(1).language("en").build());
            } catch (KugelAudioException e) {
                System.out.println("PROBE MAIN_DONE_AT=" + System.currentTimeMillis());
                System.out.flush();
                throw e; // never caught, client never closed: only the exit flush can report it
            }
            return;
        }

        try {
            client.tts().generate(GenerateRequest.builder(SECRET_TEXT).voiceId(1).language("en").build());
            System.out.println("PROBE GENERATE_ERROR=none");
        } catch (KugelAudioException e) {
            System.out.println("PROBE GENERATE_ERROR=" + e.getClass().getSimpleName());
        }
        String httpError = "none";
        for (int i = 0; i < httpCalls; i++) {
            try {
                client.models().list();
            } catch (KugelAudioException e) {
                httpError = e.getClass().getSimpleName();
            }
        }
        System.out.println("PROBE HTTP_ERROR=" + httpError);

        long started = System.nanoTime();
        client.close();
        System.out.println("PROBE CLOSE_MS=" + (System.nanoTime() - started) / 1_000_000L);
        System.out.println("PROBE MAIN_DONE_AT=" + System.currentTimeMillis());
        System.out.flush();
    }
}
