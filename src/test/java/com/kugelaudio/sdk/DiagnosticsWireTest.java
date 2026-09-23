package com.kugelaudio.sdk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Diagnostics on the real wire: a real {@link HttpServer} on a loopback port,
 * the SDK's real default sender, and one forked JVM per scenario (see
 * {@link DiagnosticsWireProbe}) so the {@code KUGELAUDIO_TELEMETRY} env var,
 * DNS and JVM exit are all real. All forks start together in
 * {@link #runScenarios()}; each test asserts one scenario.
 *
 * <p>Every scenario prefixes the API URL with its own path, so one server can
 * answer each scenario's {@code /v1/sdk-diagnostics} differently. Every other
 * route answers 401, so each SDK call fails and produces a diagnostics event.
 */
class DiagnosticsWireTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String DIAGNOSTICS_PATH = "/v1/sdk-diagnostics";

    /** One diagnostics POST as the server received it. */
    record Post(String contentType, String authorization, String body) {}

    /** What a forked probe did. */
    record Run(Map<String, String> markers, List<String> strayOutput, long exitAfterMainMs) {}

    /** Lines of the user's own uncaught-exception trace, printed by the JVM. */
    private static boolean isUncaughtTraceLine(String line) {
        return line.startsWith("Exception in thread \"main\" ")
                || line.startsWith("\tat ")
                || line.startsWith("\t... ")
                || line.startsWith("Caused by: ");
    }

    /**
     * Timing bounds. Loose enough for a loaded 2-vCPU runner running
     * {@link #FORK_PARALLELISM} JVMs at once, tight enough to prove the
     * behaviour: every bound is well under the 3 s diagnostics request
     * timeout a blocking close() or a non-daemon thread would hit.
     */
    private static final long CLOSE_BOUND_MS = 1_600;
    private static final long EXIT_AFTER_MAIN_BOUND_MS = 2_500;
    private static final long EXIT_FLUSH_ADDED_BOUND_MS = 1_800;
    private static final long IDLE_ADDED_BOUND_MS = 700;
    private static final int FORK_PARALLELISM = 4;
    private static final java.util.concurrent.ExecutorService FORKS =
            Executors.newFixedThreadPool(FORK_PARALLELISM, r -> {
                Thread t = new Thread(r, "wire-test-fork");
                t.setDaemon(true);
                return t;
            });

    private static HttpServer server;
    private static final CountDownLatch RELEASE_HANGS = new CountDownLatch(1);
    private static final Map<String, List<Post>> POSTS = new ConcurrentHashMap<>();
    private static final Map<String, Run> RUNS = new ConcurrentHashMap<>();
    private static Path hostsFile;

    @BeforeAll
    static void runScenarios() throws Exception {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "wire-test-server");
            t.setDaemon(true);
            return t;
        }));
        server.createContext("/", DiagnosticsWireTest::handle);
        server.start();
        int port = server.getAddress().getPort();
        String local = "http://127.0.0.1:" + port;
        String hosted = "http://probe.kugelaudio.com:" + port;
        hostsFile = Files.createTempFile("kugel-hosts", ".txt");
        Files.writeString(hostsFile, "127.0.0.1 probe.kugelaudio.com\n");

        List<CompletableFuture<Void>> forks = new ArrayList<>();
        // a. opt-out matrix
        forks.add(fork("optout", local + "/optout", "false", 2, null));
        forks.add(fork("custom", local + "/custom", "unset", 2, null));
        forks.add(fork("env0", local + "/env0", "true", 2, "0"));
        forks.add(fork("env1", local + "/env1", "false", 20, "1"));
        // b. hosted default
        forks.add(fork("hosted", hosted + "/hosted", "unset", 2, null));
        forks.add(fork("hostedoff", hosted + "/hostedoff", "unset", 2, "0"));
        // d. endpoint failure modes
        forks.add(fork("s404", local + "/s404", "true", 40, null));
        forks.add(fork("s500", local + "/s500", "true", 2, null));
        forks.add(fork("s413", local + "/s413", "true", 2, null));
        forks.add(fork("hang", local + "/hang", "true", 2, null));
        forks.add(fork("refused", "http://127.0.0.1:" + closedPort(), "true", 2, null));
        forks.add(fork("unresolvable", "http://unresolvable.kugelaudio.com:" + port, "unset", 2, null));
        // Exit flush: uncaught errors from never-closed clients.
        forks.add(fork("exiton", local + "/exiton", "true", 0, null, "uncaught"));
        forks.add(fork("exitoff", local + "/exitoff", "false", 0, null, "uncaught"));
        forks.add(fork("exitidle", local + "/exitidle", "true", 0, null, "idle"));
        forks.add(fork("hangexit", local + "/hangexit", "true", 0, null, "uncaught"));
        forks.add(fork("closeexit", local + "/closeexit", "true", 1, null, "close"));
        CompletableFuture.allOf(forks.toArray(new CompletableFuture[0])).get(60, TimeUnit.SECONDS);
    }

    @AfterAll
    static void stopServer() throws IOException {
        RELEASE_HANGS.countDown();
        if (server != null) server.stop(0);
        if (hostsFile != null) Files.deleteIfExists(hostsFile);
    }

    // ── server ──────────────────────────────────────────────────────────

    private static void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        try (exchange) {
            // Behave like uvicorn: a request offering an h2c upgrade loses its body.
            byte[] body = PlainHttpBodyTest.bodyAsUvicornSeesIt(exchange);
            if (!path.endsWith(DIAGNOSTICS_PATH)) {
                // Every SDK call fails with a server error, including WS upgrades.
                exchange.getResponseHeaders().add("x-request-id", "wire_req_1");
                byte[] error = "{\"error\":\"nope\",\"error_code\":\"UNAUTHORIZED\"}"
                        .getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(401, error.length);
                exchange.getResponseBody().write(error);
                return;
            }
            String scenario = path.substring(1, path.length() - DIAGNOSTICS_PATH.length());
            POSTS.computeIfAbsent(scenario, k -> new CopyOnWriteArrayList<>()).add(new Post(
                    exchange.getRequestHeaders().getFirst("Content-Type"),
                    exchange.getRequestHeaders().getFirst("Authorization"),
                    new String(body, StandardCharsets.UTF_8)));
            int status = switch (scenario) {
                case "s404" -> 404;
                case "s500" -> 500;
                case "s413" -> 413;
                default -> 202;
            };
            if (scenario.startsWith("hang")) {
                try {
                    RELEASE_HANGS.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            exchange.sendResponseHeaders(status, -1);
        }
    }

    private static List<Post> posts(String scenario) {
        return POSTS.getOrDefault(scenario, List.of());
    }

    // ── forked probe ────────────────────────────────────────────────────

    private static int closedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }

    private static CompletableFuture<Void> fork(
            String scenario, String apiUrl, String telemetry, int httpCalls, String envTelemetry) {
        return fork(scenario, apiUrl, telemetry, httpCalls, envTelemetry, "close");
    }

    private static CompletableFuture<Void> fork(String scenario, String apiUrl, String telemetry,
                                                int httpCalls, String envTelemetry, String mode) {
        return CompletableFuture.runAsync(() -> {
            try {
                RUNS.put(scenario, runProbe(apiUrl, telemetry, httpCalls, envTelemetry, mode));
            } catch (Exception e) {
                throw new IllegalStateException(scenario + ": probe did not run", e);
            }
        }, FORKS);
    }

    private static Run runProbe(
            String apiUrl, String telemetry, int httpCalls, String envTelemetry, String mode)
            throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        File out = File.createTempFile("probe", ".out");
        File err = File.createTempFile("probe", ".err");
        try {
            ProcessBuilder builder = new ProcessBuilder(
                    java,
                    "-Djdk.net.hosts.file=" + hostsFile,
                    "-cp", System.getProperty("java.class.path"),
                    DiagnosticsWireProbe.class.getName(),
                    apiUrl, telemetry, Integer.toString(httpCalls), mode)
                    .redirectOutput(out)
                    .redirectError(err);
            builder.environment().remove("KUGELAUDIO_TELEMETRY");
            if (envTelemetry != null) builder.environment().put("KUGELAUDIO_TELEMETRY", envTelemetry);
            Process process = builder.start();
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                fail(apiUrl + ": probe JVM did not exit within 30 s");
            }
            long exitedAt = System.currentTimeMillis();

            Map<String, String> markers = new LinkedHashMap<>();
            List<String> stray = new ArrayList<>();
            List<String> lines = new ArrayList<>(Files.readAllLines(out.toPath()));
            lines.addAll(Files.readAllLines(err.toPath()));
            for (String line : lines) {
                if (line.startsWith("PROBE ")) {
                    String[] kv = line.substring(6).split("=", 2);
                    markers.put(kv[0], kv[1]);
                } else if (!line.isBlank()) {
                    stray.add(line);
                }
            }
            long mainDone = Long.parseLong(markers.getOrDefault("MAIN_DONE_AT", "0"));
            return new Run(markers, stray, mainDone == 0 ? Long.MAX_VALUE : exitedAt - mainDone);
        } finally {
            Files.deleteIfExists(out.toPath());
            Files.deleteIfExists(err.toPath());
        }
    }

    /** Checks every scenario shares: SDK error intact, bounded close, prompt exit, silence. */
    private static void assertCleanRun(String scenario, String expectedError) {
        Run run = RUNS.get(scenario);
        assertNotNull(run, scenario + " did not run");
        assertEquals(expectedError, run.markers().get("GENERATE_ERROR"), scenario + ": generate error changed");
        assertEquals(expectedError, run.markers().get("HTTP_ERROR"), scenario + ": HTTP error changed");
        long closeMs = Long.parseLong(run.markers().get("CLOSE_MS"));
        assertTrue(closeMs <= CLOSE_BOUND_MS, scenario + ": close() took " + closeMs + " ms");
        assertTrue(run.exitAfterMainMs() <= EXIT_AFTER_MAIN_BOUND_MS,
                scenario + ": JVM exited " + run.exitAfterMainMs() + " ms after main returned");
        assertEquals(List.of(), run.strayOutput(), scenario + ": SDK printed output");
    }

    private static List<JsonNode> records(Post post) throws IOException {
        List<JsonNode> out = new ArrayList<>();
        MAPPER.readTree(post.body()).path("resourceLogs").get(0).path("scopeLogs").get(0)
                .path("logRecords").forEach(out::add);
        return out;
    }

    // ── a. opt-out matrix ───────────────────────────────────────────────

    @Test
    void telemetryFalseSendsNothing() {
        assertCleanRun("optout", "AuthenticationException");
        assertEquals(0, posts("optout").size());
    }

    @Test
    void customUrlWithOptionUnsetSendsNothing() {
        assertCleanRun("custom", "AuthenticationException");
        assertEquals(0, posts("custom").size());
    }

    @Test
    void envZeroBeatsTheOptionTrue() {
        assertCleanRun("env0", "AuthenticationException");
        assertEquals(0, posts("env0").size());
    }

    @Test
    void envOneBeatsTheOptionFalse() {
        assertCleanRun("env1", "AuthenticationException");
        assertFalse(posts("env1").isEmpty());
    }

    // ── b. hosted default ───────────────────────────────────────────────

    @Test
    void hostedEndpointIsOnByDefault() {
        assertCleanRun("hosted", "AuthenticationException");
        assertFalse(posts("hosted").isEmpty(), "a *.kugelaudio.com base URL reports by default");
    }

    @Test
    void envZeroSilencesTheHostedDefault() {
        assertCleanRun("hostedoff", "AuthenticationException");
        assertEquals(0, posts("hostedoff").size());
    }

    // ── c. payload ──────────────────────────────────────────────────────

    @Test
    void payloadIsJsonAuthenticatedBatchedAndFreeOfInput() throws IOException {
        List<Post> sent = posts("env1");
        assertFalse(sent.isEmpty());
        int total = 0;
        for (Post post : sent) {
            assertEquals("application/json", post.contentType());
            assertEquals("Bearer " + DiagnosticsWireProbe.API_KEY, post.authorization());
            int size = records(post).size();
            assertTrue(size >= 1 && size <= 8, "records per POST: " + size);
            total += size;
            assertFalse(post.body().contains(DiagnosticsWireProbe.SECRET_TEXT), "input text leaked");
            assertFalse(post.body().contains(DiagnosticsWireProbe.API_KEY), "API key leaked");
            assertFalse(post.body().contains("127.0.0.1"), "host leaked");
        }
        // 1 generate + 20 HTTP failures + the sdk_stats roll-up.
        assertEquals(22, total);
    }

    // ── d. endpoint failure modes ───────────────────────────────────────

    @Test
    void threeNotFoundsThenSilence() {
        assertCleanRun("s404", "AuthenticationException");
        assertEquals(3, posts("s404").size(), "no POST after the third 404");
    }

    @Test
    void serverErrorIsRetriedOnceAndSwallowed() {
        assertCleanRun("s500", "AuthenticationException");
        assertEquals(2, posts("s500").size(), "one batch: one attempt plus one retry");
    }

    @Test
    void tooLargeIsNotRetriedAndSwallowed() {
        assertCleanRun("s413", "AuthenticationException");
        assertEquals(1, posts("s413").size(), "one batch, never retried");
    }

    @Test
    void hangingEndpointNeitherBlocksCloseNorExit() {
        assertCleanRun("hang", "AuthenticationException");
        assertEquals(1, posts("hang").size());
    }

    // ── Exit flush ──────────────────────────────────────────────────────

    /** The run's stray output must be exactly one uncaught trace for main, nothing else. */
    private static void assertOnlyTheUsersTrace(String scenario) {
        Run run = RUNS.get(scenario);
        assertNotNull(run, scenario + " did not run");
        List<String> other = run.strayOutput().stream().filter(l -> !isUncaughtTraceLine(l)).toList();
        assertEquals(List.of(), other, scenario + ": output beyond the user's own trace");
        long headers = run.strayOutput().stream().filter(l -> l.startsWith("Exception in thread")).count();
        assertEquals(1, headers, scenario + ": exactly one uncaught trace, for main");
        assertTrue(run.strayOutput().get(0).contains("AuthenticationException"), run.strayOutput().get(0));
    }

    @Test
    void uncaughtErrorOfANeverClosedClientIsReportedAtExit() throws IOException {
        assertOnlyTheUsersTrace("exiton");
        assertOnlyTheUsersTrace("exitoff");
        assertEquals(1, posts("exiton").size(), "the exit flush delivered the queued record");
        assertEquals(1, records(posts("exiton").get(0)).size());
        assertEquals(0, posts("exitoff").size());
        long added = RUNS.get("exiton").exitAfterMainMs() - RUNS.get("exitoff").exitAfterMainMs();
        assertTrue(added <= EXIT_FLUSH_ADDED_BOUND_MS, "exit flush added " + added + " ms");
    }

    @Test
    void nothingQueuedMeansNoExitDelay() {
        Run idle = RUNS.get("exitidle");
        assertEquals(List.of(), idle.strayOutput());
        assertEquals(0, posts("exitidle").size());
        long added = idle.exitAfterMainMs() - RUNS.get("exitoff").exitAfterMainMs();
        assertTrue(added <= IDLE_ADDED_BOUND_MS, "idle client delayed exit by " + added + " ms");
    }

    @Test
    void hungEndpointDelaysExitByAtMostTheDeadline() {
        assertOnlyTheUsersTrace("hangexit");
        assertEquals(1, posts("hangexit").size());
        long added = RUNS.get("hangexit").exitAfterMainMs() - RUNS.get("exitoff").exitAfterMainMs();
        assertTrue(added <= EXIT_FLUSH_ADDED_BOUND_MS, "hung endpoint delayed exit by " + added + " ms");
    }

    @Test
    void explicitCloseMakesTheExitFlushANoOp() {
        assertCleanRun("closeexit", "AuthenticationException");
        assertEquals(1, posts("closeexit").size(), "close() delivered once; the exit hook sent nothing more");
    }

    @Test
    void refusedConnectionIsSilent() {
        assertCleanRun("refused", "ConnectionException");
    }

    @Test
    void unresolvableHostIsSilent() {
        assertCleanRun("unresolvable", "ConnectionException");
    }
}
