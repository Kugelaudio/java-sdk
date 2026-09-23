<p align="center">
  <a href="https://kugelaudio.com">
    <picture>
      <source media="(prefers-color-scheme: dark)" srcset=".github/logo-dark.svg">
      <source media="(prefers-color-scheme: light)" srcset=".github/logo-light.svg">
      <img alt="KugelAudio" src=".github/logo-light.svg" width="320">
    </picture>
  </a>
</p>

<p align="center">
  <strong>Ultra-low latency text-to-speech for real-time applications</strong>
</p>

<p align="center">
  <a href="https://central.sonatype.com/artifact/com.kugelaudio/kugelaudio"><img src="https://img.shields.io/maven-central/v/com.kugelaudio/kugelaudio?style=flat-square&label=Maven%20Central" alt="Maven Central"></a>
  <a href="https://github.com/Kugelaudio/java-sdk/blob/main/LICENSE"><img src="https://img.shields.io/github/license/Kugelaudio/java-sdk?style=flat-square" alt="License"></a>
  <a href="https://docs.kugelaudio.com/sdks/java"><img src="https://img.shields.io/badge/docs-kugelaudio.com-6366F1?style=flat-square" alt="Documentation"></a>
</p>

<p align="center">
  <a href="https://docs.kugelaudio.com/sdks/java">Documentation</a> · <a href="https://kugelaudio.com/dashboard">Get API Key</a> · <a href="https://docs.kugelaudio.com">API Reference</a> · <a href="https://github.com/kugelaudio">GitHub</a>
</p>

---

# KugelAudio Java SDK

Official Java SDK for the [KugelAudio](https://kugelaudio.com) Text-to-Speech
API — one-shot generation, WebSocket streaming, LLM sessions, multi-context
sessions, voice cloning, dictionaries, and word timestamps. Requires Java 17+.

📖 **[Full documentation →](https://docs.kugelaudio.com/sdks/java/quickstart)**

## Installation

**Maven:**

```xml
<dependency>
  <groupId>com.kugelaudio</groupId>
  <artifactId>kugelaudio</artifactId>
  <version>3.0.0</version>
</dependency>
```

**Gradle:**

```groovy
implementation 'com.kugelaudio:kugelaudio:3.0.0'
```

## Quick start

```java
import com.kugelaudio.sdk.*;
import java.nio.file.Path;

KugelAudio client = new KugelAudio(KugelAudioOptions.builder("your_api_key").build());

AudioResponse audio = client.tts().generate(
    GenerateRequest.builder("Hello, world!")
        .modelId("kugel-3")
        .voiceId(1071)
        .language("en")   // skip auto-detection (~150ms) when you know the language
        .build()
);
audio.saveWav(Path.of("output.wav"));
client.close();
```

The client opens its WebSocket in the background on construction, so the
handshake is off the first request's hot path. `client.connect()` blocks until
it is ready; `KugelAudio.createConnected(options)` does both.

[`kugel-3`](https://docs.kugelaudio.com/models) is the current model; legacy IDs
(`kugel-1-turbo`, `kugel-2.5`, …) are still accepted.

## Streaming

```java
client.tts().stream(
    GenerateRequest.builder("Hello, this is streaming audio.").voiceId(1071).build(),
    new StreamCallbacks() {
        @Override public void onChunk(AudioChunk chunk) { playAudio(chunk.getAudio()); }
        @Override public void onComplete(AudioResponse response) { /* stats */ }
        @Override public void onError(KugelAudioException error) { /* handle */ }
    }
);
```

For text arriving from an LLM, use a streaming session: forward raw tokens and
the server chunks them at sentence boundaries.

```java
StreamConfig config = StreamConfig.builder().voiceId(1071).language("en").build();

try (StreamingSession session = client.streamingSession(config, callbacks)) {
    for (String token : llmTokens) {
        session.send(token);   // flush=false (default)
    }
    session.flush();           // once, at turn end
}
```

> ⚠️ Do **not** call `session.send(text, true)` between sentences. Every explicit
> flush is a separate request that pays time-to-first-audio again and leaves an
> audible gap — see
> [Chunking & latency](https://docs.kugelaudio.com/streaming/chunking-and-latency).

`client.multiContextSession(config)` drives several independent speakers over one
connection — see
[multi-context](https://docs.kugelaudio.com/streaming/multi-context),
[barge-in](https://docs.kugelaudio.com/streaming/barge-in), and
[word timestamps](https://docs.kugelaudio.com/streaming/word-timestamps).

## Agent skill

If you build with a coding agent, install the KugelAudio skill so it gets the
TTFA rules, streaming semantics, and text-formatting constraints without you
re-explaining them. Maven has no install hook, so pull it from the npm package:

```bash
npx -p kugelaudio kugelaudio-skills install   # → ./.claude/skills/kugelaudio-tts/
```

## Regions

`api.kugelaudio.com` is geo-routed by default. To pin traffic to the EU, prefix
the key (`eu-ka_…`) or pass `.region(Region.EU)` —
[details](https://docs.kugelaudio.com/guides/regions).

## Errors

All errors extend `KugelAudioException`: `AuthenticationException`,
`RateLimitException`, `InsufficientCreditsException`, `ValidationException`,
`NotFoundException`, `ConnectionException`. See the
[error reference](https://docs.kugelaudio.com/api-reference/errors).

Every error carries `getRequestId()` — the server's `x-request-id` for HTTP
calls, or the `request_id` of a WebSocket error frame. Quote it in a support
request and we can find the exact call in our logs.

## Diagnostics

When you talk to a hosted `*.kugelaudio.com` endpoint, the SDK reports the
*shape* of failed calls so we can fix what breaks in the field: event kind,
failure stage, error class, elapsed time, chunk counts, HTTP status and the
server request id. It never sends your text, audio, API key, URLs, hostnames or
exception messages, and delivery happens on a daemon thread that cannot delay a
synthesis call or JVM exit. Reports go to `/v1/sdk-diagnostics` on the same API
base URL you already configured, authenticated with your own API key — there is
no separate telemetry host and no shared ingestion token.

Off by default for custom and on-premise base URLs. Turn it off explicitly:

```java
KugelAudioOptions.builder(apiKey).telemetry(false).build();
```

or set `KUGELAUDIO_TELEMETRY=0` in the environment, which overrides the builder
in both directions.

## Documentation

| Topic | Link |
|---|---|
| Client options, auth, regions | [Configuration](https://docs.kugelaudio.com/sdks/java/configuration) |
| Generation, streaming, timestamps | [Generate](https://docs.kugelaudio.com/sdks/java/generate) |
| Streaming, barge-in, multi-context | [LLM Sessions](https://docs.kugelaudio.com/sdks/java/llm-sessions) |
| List, create, clone voices | [Voices](https://docs.kugelaudio.com/sdks/java/voices) |
| Pronunciation dictionaries | [Dictionaries](https://docs.kugelaudio.com/sdks/java/dictionaries) |
| Data models & audio utilities | [Types](https://docs.kugelaudio.com/sdks/java/types) |

## Acknowledgments

Kudos to **Schtief** for providing the first version of this SDK!

## License

[MIT](LICENSE)
