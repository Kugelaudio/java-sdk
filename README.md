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

The official Java SDK for the [KugelAudio](https://kugelaudio.com) Text-to-Speech API. Generate high-quality speech with ~39ms time-to-first-audio, WebSocket streaming, LLM integration, voice cloning, word timestamps, and multi-language support across 25 languages.

## Requirements

- Java 17+
- Maven 3.8+ or Gradle 7+

## Installation

**Maven:**

```xml
<dependency>
  <groupId>com.kugelaudio</groupId>
  <artifactId>kugelaudio</artifactId>
  <version>1.0.1</version>
</dependency>
```

**Gradle (Groovy):**

```groovy
implementation 'com.kugelaudio:kugelaudio:1.0.1'
```

**Gradle (Kotlin DSL):**

```kotlin
implementation("com.kugelaudio:kugelaudio:1.0.1")
```

## Quick Start

```java
import com.kugelaudio.sdk.*;
import java.nio.file.Path;

KugelAudio client = new KugelAudio(
    KugelAudioOptions.builder("your_api_key").build()
);

AudioResponse audio = client.tts().generate(
    GenerateRequest.builder("Hello, world!")
        .modelId("kugel-1-turbo")
        .language("en")
        .build()
);

audio.saveWav(Path.of("output.wav"));
client.close();
```

## Client Configuration

```java
// Read API key from KUGELAUDIO_API_KEY environment variable
KugelAudio client = KugelAudio.fromEnv();

// Or with full configuration
KugelAudio client = new KugelAudio(
    KugelAudioOptions.builder("your_api_key")
        .apiUrl("https://api.kugelaudio.com")  // REST + WebSocket base URL
        .timeout(Duration.ofSeconds(60))        // HTTP request timeout
        .autoConnect(true)                      // Pre-connect WebSocket (default: true)
        .build()
);
```

The SDK uses a **single URL** for both REST API and WebSocket streaming. By default, the WebSocket connection is established in the background at construction time (`autoConnect = true`), so the ~300-500ms handshake is absorbed at startup rather than on the first request.

```java
// Block until connection is ready
KugelAudio client = KugelAudio.createConnected(
    KugelAudioOptions.builder("your_api_key").build()
);
```

## Available Models

| Model ID | Name | Parameters | Best For |
|----------|------|------------|----------|
| `kugel-1-turbo` | Kugel 1 Turbo | 1.5B | Real-time applications (~39ms TTFA) |
| `kugel-1` | Kugel 1 | 7B | Premium quality pre-recorded content |

```java
for (Model model : client.models().list()) {
    System.out.printf("%s: %s (%s)%n", model.getId(), model.getName(), model.getParameters());
}
```

## Text-to-Speech

### Basic Generation

```java
AudioResponse audio = client.tts().generate(
    GenerateRequest.builder("Hello, this is a test of KugelAudio.")
        .modelId("kugel-1-turbo")
        .voiceId(123)
        .cfgScale(2.0)
        .sampleRate(24000)
        .normalize(true)
        .language("en")
        .build()
);

System.out.printf("Duration: %.0fms | Generated in: %.0fms | RTF: %.2f%n",
    audio.getDurationMs(), audio.getGenerationMs(), audio.getRtf());

audio.saveWav(Path.of("output.wav"));
```

### Streaming

Receive audio chunks as they are generated for lower time-to-first-audio:

```java
client.tts().stream(
    GenerateRequest.builder("Hello, this is streaming audio.")
        .modelId("kugel-1-turbo")
        .language("en")
        .build(),
    new StreamCallbacks() {
        @Override
        public void onChunk(AudioChunk chunk) {
            playAudio(chunk.getAudio());
        }

        @Override
        public void onComplete(AudioResponse response) {
            response.saveWav(Path.of("output.wav"));
        }

        @Override
        public void onError(KugelAudioException error) {
            System.err.println("Error: " + error.getMessage());
        }
    }
);
```

### Word Timestamps

Get word-level time alignments for subtitles, lip-sync, or barge-in handling:

```java
AudioResponse audio = client.tts().generate(
    GenerateRequest.builder("Hello, how are you today?")
        .modelId("kugel-1-turbo")
        .language("en")
        .wordTimestamps(true)
        .build()
);

for (WordTimestamp ts : audio.getWordTimestamps()) {
    System.out.printf("%s: %dms - %dms (score: %.2f)%n",
        ts.getWord(), ts.getStartMs(), ts.getEndMs(), ts.getScore());
}
```

## LLM Integration

Stream text tokens from an LLM directly into a `StreamingSession` for real-time text-to-speech:

```java
StreamConfig config = StreamConfig.builder()
    .voiceId(123)
    .modelId("kugel-1-turbo")
    .language("en")
    .flushTimeoutMs(500)
    .build();

try (StreamingSession session = client.streamingSession(config, new StreamCallbacks() {
    @Override
    public void onChunk(AudioChunk chunk) {
        playAudio(chunk.getAudio());
    }
})) {
    for (String token : llmTokens) {
        session.send(token);
    }
    session.flush();
}
```

## Multi-Context Sessions

Generate audio for multiple speakers concurrently over a single WebSocket connection:

```java
MultiContextConfig config = MultiContextConfig.builder()
    .language("en")
    .sampleRate(24000)
    .build();

try (MultiContextSession session = client.multiContextSession(config)) {
    session.connect(new MultiContextCallbacks() {
        @Override
        public void onChunk(String contextId, AudioChunk chunk) {
            playAudio(contextId, chunk.getAudio());
        }

        @Override
        public void onContextComplete(String contextId) {
            System.out.println("[" + contextId + "] done");
        }
    });

    session.createContext("speaker-1", CreateContextOptions.builder().voiceId(101).build());
    session.createContext("speaker-2", CreateContextOptions.builder().voiceId(202).build());

    session.send("speaker-1", "Hello from speaker one.");
    session.send("speaker-2", "And hello from speaker two.");

    session.flush("speaker-1");
    session.flush("speaker-2");
}
```

## Voices

```java
// List voices
List<Voice> voices = client.voices().list();

// Filter by language
List<Voice> germanVoices = client.voices().list("de", true, 10);

// Get voice details
VoiceDetail voice = client.voices().get(123);

// Create a custom voice (voice cloning)
VoiceDetail custom = client.voices().create(
    "My Voice", "female", "en",
    List.of(Path.of("reference1.wav"), Path.of("reference2.wav"))
);

// Manage references
client.voices().addReference(123, Path.of("new_ref.wav"), "Transcript text.");
client.voices().deleteReference(123, 456);

// Publish a voice for public use
client.voices().publish(123);
```

## Text Normalization

Text normalization converts numbers, dates, and symbols into spoken words. Supports 25 languages.

```java
AudioResponse audio = client.tts().generate(
    GenerateRequest.builder("I bought 3 items for $50.99 on 01/15/2024.")
        .normalize(true)
        .language("en")
        .build()
);
```

Use `<spell>` tags to spell out text letter by letter (emails, codes, acronyms):

```java
AudioResponse audio = client.tts().generate(
    GenerateRequest.builder("Contact me at <spell>kajo@kugelaudio.com</spell>")
        .normalize(true)
        .language("en")
        .build()
);
```

> **Tip:** Always specify `.language()` for best performance. Omitting it triggers auto-detection which may produce incorrect normalizations for short texts.

### Supported Languages

| Code | Language | Code | Language | Code | Language |
|------|----------|------|----------|------|----------|
| `de` | German | `nl` | Dutch | `ar` | Arabic |
| `en` | English | `pl` | Polish | `hi` | Hindi |
| `fr` | French | `sv` | Swedish | `zh` | Chinese |
| `es` | Spanish | `da` | Danish | `ja` | Japanese |
| `it` | Italian | `no` | Norwegian | `ko` | Korean |
| `pt` | Portuguese | `fi` | Finnish | `el` | Greek |
| `cs` | Czech | `hu` | Hungarian | `bg` | Bulgarian |
| `ro` | Romanian | `uk` | Ukrainian | `vi` | Vietnamese |
| `tr` | Turkish | | | | |

## Error Handling

```java
try {
    AudioResponse audio = client.tts().generate(
        GenerateRequest.builder("Hello!").language("en").build()
    );
} catch (AuthenticationException e) {
    // Invalid API key
} catch (RateLimitException e) {
    // Rate limit exceeded
} catch (InsufficientCreditsException e) {
    // Not enough credits
} catch (ValidationException e) {
    // Invalid request parameters
} catch (ConnectionException e) {
    // Network / WebSocket failure
} catch (KugelAudioException e) {
    // Other API errors
}
```

## Audio Utilities

```java
import com.kugelaudio.sdk.AudioFormats;

// Write PCM16 to WAV
AudioFormats.writePcm16Wav(Path.of("out.wav"), pcmBytes, 24000, (short) 1);

// Duration in milliseconds
int durationMs = AudioFormats.durationMs(pcmBytes, 24000, 16, 1);

// PCM16 <-> float32
float[] floats = AudioFormats.pcm16ToFloat32(pcmBytes);

// PCM16 <-> μ-law (telephony / Twilio)
byte[] ulaw = AudioFormats.pcm16ToUlaw(pcmBytes);
byte[] pcm = AudioFormats.ulawToPcm16(ulawBytes);
```

## Documentation

For the full API reference, guides, and integration examples visit **[docs.kugelaudio.com](https://docs.kugelaudio.com/sdks/java)**.

## Acknowledgments

Kudos to **Schtief** for providing the first version of this SDK!

## License

[MIT](LICENSE)
