# KugelAudio Java SDK

Official Java SDK for the KugelAudio Text-to-Speech API.

## Requirements

- Java 17+
- Maven 3.8+

## Installation

Add to your `pom.xml`:

```xml
<dependency>
  <groupId>com.kugelaudio</groupId>
  <artifactId>kugelaudio</artifactId>
  <version>0.1.0</version>
</dependency>
```

Or with Gradle:

```groovy
implementation 'com.kugelaudio:kugelaudio:0.1.0'
```

## Quick Start

```java
import com.kugelaudio.sdk.*;
import java.nio.file.Path;

// Initialize the client
KugelAudio client = new KugelAudio(
    KugelAudioOptions.builder("your_api_key").build()
);

// Generate speech
AudioResponse response = client.tts().generate(
    GenerateRequest.builder("Hello, world!").voiceId(123).build()
);

// Save to file
response.saveWav(Path.of("output.wav"));

// Clean up
client.close();
```

## Client Configuration

```java
import com.kugelaudio.sdk.*;
import java.time.Duration;

// Simple setup - single URL handles everything
KugelAudio client = new KugelAudio(
    KugelAudioOptions.builder("your_api_key").build()
);

// Or with custom options
KugelAudio client = new KugelAudio(
    KugelAudioOptions.builder("your_api_key")
        .apiUrl("https://api.kugelaudio.com")   // Optional: API base URL (default)
        .timeout(Duration.ofSeconds(60))         // Optional: Request timeout (default: 60s)
        .build()
);

// Or read API key from KUGELAUDIO_API_KEY environment variable
KugelAudio client = KugelAudio.fromEnv();
```

### Single URL Architecture

The SDK uses a **single URL** for both REST API and WebSocket streaming. The TTS server provides both REST endpoints (`/v1/models`, `/v1/voices`) and WebSocket (`/ws/tts`) - no proxy needed, minimal latency.

### Local Development

For local development, point directly to your TTS server:

```java
KugelAudio client = new KugelAudio(
    KugelAudioOptions.builder("your_api_key")
        .apiUrl("http://localhost:8000")   // TTS server handles everything
        .build()
);
```

Or if you have separate backend and TTS servers:

```java
KugelAudio client = new KugelAudio(
    KugelAudioOptions.builder("your_api_key")
        .apiUrl("http://localhost:8001")   // Backend for REST API
        .ttsUrl("http://localhost:8000")   // TTS server for WebSocket streaming
        .build()
);
```

## Available Models

| Model ID | Name | Parameters | Description |
|----------|------|------------|-------------|
| `kugel-1-turbo` | Kugel 1 Turbo | 1.5B | Fast, low-latency model for real-time applications |
| `kugel-1` | Kugel 1 | 7B | Premium quality model for pre-recorded content |

### List Available Models

```java
List<Model> models = client.models().list();

for (Model model : models) {
    System.out.println(model.getId() + ": " + model.getName());
    System.out.println("  Description: " + model.getDescription());
    System.out.println("  Parameters: " + model.getParameters());
    System.out.println("  Max Input: " + model.getMaxInputLength() + " characters");
    System.out.println("  Sample Rate: " + model.getSampleRate() + " Hz");
}
```

## Voices

### List Available Voices

```java
List<Voice> voices = client.voices().list();

for (Voice voice : voices) {
    System.out.println(voice.getId() + ": " + voice.getName());
    System.out.println("  Category: " + voice.getCategory());
}
```

### Get a Specific Voice

```java
VoiceDetail voice = client.voices().get(123);
System.out.println("Voice: " + voice.getName());
System.out.println("References: " + voice.getReferences().size());
```

## Text-to-Speech Generation

### Basic Generation (Non-Streaming)

Generate complete audio and receive it all at once:

```java
AudioResponse audio = client.tts().generate(
    GenerateRequest.builder("Hello, this is a test of the KugelAudio TTS system.")
        .modelId("kugel-1-turbo")   // "kugel-1-turbo" (fast) or "kugel-1" (quality)
        .voiceId(123)               // Optional: specific voice ID
        .cfgScale(2.0)              // Guidance scale (1.0-5.0)
        .maxNewTokens(2048)         // Maximum tokens to generate
        .sampleRate(24000)          // Output sample rate
        .normalize(true)            // Enable text normalization
        .language("en")             // Language for normalization
        .build()
);

// Audio properties
System.out.println("Duration: " + audio.getDurationMs() + "ms");
System.out.println("Samples: " + audio.getSamples());
System.out.println("Sample rate: " + audio.getSampleRate() + " Hz");
System.out.println("Generation time: " + audio.getGenerationMs() + "ms");
System.out.println("RTF: " + audio.getRtf());

// Save to WAV file
audio.saveWav(Path.of("output.wav"));

// Get raw PCM bytes
byte[] pcmData = audio.getAudio();

// Get WAV bytes (with header)
byte[] wavBytes = audio.toWavBytes();
```

### Streaming Audio Output

Receive audio chunks as they are generated for lower latency:

```java
client.tts().stream(
    GenerateRequest.builder("Hello, this is streaming audio.")
        .modelId("kugel-1-turbo")
        .build(),
    new StreamCallbacks() {
        @Override
        public void onChunk(AudioChunk chunk) {
            System.out.println("Chunk " + chunk.getIndex() + ": "
                + chunk.getAudio().length + " bytes, "
                + chunk.getSamples() + " samples");
        }

        @Override
        public void onComplete(AudioResponse response) {
            System.out.println("Total duration: " + response.getDurationMs() + "ms");
            response.saveWav(Path.of("output.wav"));
        }

        @Override
        public void onError(KugelAudioException error) {
            System.err.println("Error: " + error.getMessage());
        }
    }
);
```

### Connection Pooling

Pre-establish the WebSocket connection for faster first request:

```java
// Option 1: Pre-connect during client creation
KugelAudio client = KugelAudio.createConnected(
    KugelAudioOptions.builder("your_api_key").build()
);

// Option 2: Explicitly connect later
client.connect();

// Check connection status
boolean connected = client.isConnected();
```

## Text Normalization

Text normalization converts numbers, dates, times, and other non-verbal text into spoken words. For example:
- "I have 3 apples" -> "I have three apples"
- "The meeting is at 2:30 PM" -> "The meeting is at two thirty PM"
- "$50.99" -> "fifty dollars and ninety-nine cents"

### Usage

```java
// With explicit language (recommended - fastest)
AudioResponse audio = client.tts().generate(
    GenerateRequest.builder("I bought 3 items for $50.99 on 01/15/2024.")
        .normalize(true)
        .language("en")   // Specify language for best performance
        .build()
);

// With auto-detection (adds ~150ms latency)
AudioResponse audio = client.tts().generate(
    GenerateRequest.builder("Ich habe 3 Artikel fur 50,99 Euro gekauft.")
        .normalize(true)
        // language not specified - will auto-detect
        .build()
);
```

### Supported Languages

| Code | Language | Code | Language |
|------|----------|------|----------|
| `de` | German | `nl` | Dutch |
| `en` | English | `pl` | Polish |
| `fr` | French | `sv` | Swedish |
| `es` | Spanish | `da` | Danish |
| `it` | Italian | `no` | Norwegian |
| `pt` | Portuguese | `fi` | Finnish |
| `cs` | Czech | `hu` | Hungarian |
| `ro` | Romanian | `el` | Greek |
| `uk` | Ukrainian | `bg` | Bulgarian |
| `tr` | Turkish | `vi` | Vietnamese |
| `ar` | Arabic | `hi` | Hindi |
| `zh` | Chinese | `ja` | Japanese |
| `ko` | Korean | | |

> **Latency Warning**: Using `normalize(true)` without specifying `language` adds approximately **150ms latency** for language auto-detection. For best performance in latency-sensitive applications, always specify the `language` parameter.

## LLM Integration: Streaming Text Input

For real-time TTS when streaming text from an LLM (like GPT-4, Claude, etc.), use a `StreamingSession`:

```java
StreamConfig config = StreamConfig.builder()
    .voiceId(123)
    .cfgScale(2.0)
    .flushTimeoutMs(500)   // Auto-flush after 500ms of no input
    .build();

try (StreamingSession session = client.streamingSession(config, new StreamCallbacks() {
    @Override
    public void onChunk(AudioChunk chunk) {
        playAudio(chunk.getAudio());
    }
})) {
    // Simulate LLM token stream
    String[] llmTokens = {"Hello, ", "this ", "is ", "a ", "streamed ", "response."};

    for (String token : llmTokens) {
        session.sendText(token);
    }

    session.flush();
}
```

## Multi-Context Streaming

For concurrent speakers (up to 5) over a single WebSocket connection:

```java
MultiContextConfig config = MultiContextConfig.builder()
    .sampleRate(24000)
    .wordTimestamps(true)
    .build();

MultiContextCallbacks callbacks = new MultiContextCallbacks() {
    @Override
    public void onChunk(String contextId, AudioChunk chunk) {
        System.out.println("[" + contextId + "] chunk: " + chunk.getAudio().length + " bytes");
    }

    @Override
    public void onContextComplete(String contextId) {
        System.out.println("[" + contextId + "] done");
    }
};

try (MultiContextSession session = client.multiContextSession(config)) {
    session.connect(callbacks);

    // Create two independent contexts
    session.createContext("speaker-1", CreateContextOptions.builder()
        .voiceId(101).modelId("kugel-1-turbo").build());
    session.createContext("speaker-2", CreateContextOptions.builder()
        .voiceId(202).modelId("kugel-1-turbo").build());

    // Send text independently
    session.send("speaker-1", "Hello from speaker one.");
    session.send("speaker-2", "And hello from speaker two.");

    session.flush("speaker-1");
    session.flush("speaker-2");
    session.closeContext("speaker-1");
    session.closeContext("speaker-2");
}
```

## Error Handling

```java
import com.kugelaudio.sdk.*;

try {
    AudioResponse audio = client.tts().generate(
        GenerateRequest.builder("Hello!").build()
    );
} catch (AuthenticationException e) {
    System.err.println("Invalid API key");
} catch (RateLimitException e) {
    System.err.println("Rate limit exceeded, please wait");
} catch (InsufficientCreditsException e) {
    System.err.println("Not enough credits, please top up");
} catch (ValidationException e) {
    System.err.println("Invalid request: " + e.getMessage());
} catch (ConnectionException e) {
    System.err.println("Failed to connect to server");
} catch (KugelAudioException e) {
    System.err.println("API error: " + e.getMessage());
}
```

## Audio Utilities

### WAV Export

```java
// Save AudioResponse to WAV
audio.saveWav(Path.of("output.wav"));

// Get WAV bytes in memory
byte[] wavBytes = audio.toWavBytes();
```

### PCM16 to Float32

```java
// Convert raw PCM16 bytes to float samples
float[] floatData = AudioFormats.pcm16ToFloat32(audio.getAudio());
```

### Audio Duration

```java
// Calculate duration from PCM16 bytes
double durationMs = AudioFormats.durationMs(pcmBytes, 24000);
```

## Complete Example

```java
import com.kugelaudio.sdk.*;
import java.nio.file.Path;

public class Example {
    public static void main(String[] args) {
        // Initialize client
        KugelAudio client = new KugelAudio(
            KugelAudioOptions.builder("your_api_key").build()
        );

        // List available models
        System.out.println("Available Models:");
        for (Model model : client.models().list()) {
            System.out.println("  - " + model.getId() + ": "
                + model.getName() + " (" + model.getParameters() + ")");
        }

        // List available voices
        System.out.println("\nAvailable Voices:");
        for (Voice voice : client.voices().list()) {
            System.out.println("  - " + voice.getId() + ": " + voice.getName());
        }

        // Generate audio
        System.out.println("\nGenerating audio...");
        AudioResponse audio = client.tts().generate(
            GenerateRequest.builder(
                "Welcome to KugelAudio. This is an example of high-quality TTS."
            ).modelId("kugel-1-turbo").build()
        );

        System.out.println("Generated " + audio.getDurationMs() + "ms of audio in "
            + audio.getGenerationMs() + "ms");
        System.out.println("Real-time factor: " + audio.getRtf() + "x");

        // Save to file
        audio.saveWav(Path.of("example.wav"));
        System.out.println("Saved to example.wav");

        // Clean up
        client.close();
    }
}
```

## Acknowledgments

Kudos to **Schtief** for providing the first version of this SDK!

## License

MIT
