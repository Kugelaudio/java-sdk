package com.kugelaudio.sdk;

import com.kugelaudio.sdk.internal.HttpHelper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Speech-to-text uploads through public KugelAudio ingress. */
public final class ASRResource {
    public static final String MODEL_ID = "luchs-1";
    private final HttpHelper http;

    ASRResource(HttpHelper http) {
        this.http = http;
    }

    public TranscriptionResponse transcribe(byte[] audio) {
        return transcribe(audio, "audio.wav", "audio/wav", null, MODEL_ID);
    }

    public TranscriptionResponse transcribe(
            byte[] audio,
            String filename,
            String contentType,
            String language,
            String model) {
        if (audio == null || audio.length == 0) {
            throw new ValidationException("ASR audio must not be empty");
        }
        if (!MODEL_ID.equals(model)) {
            throw new ValidationException("Unsupported ASR model; use " + MODEL_ID);
        }
        String boundary = UUID.randomUUID().toString();
        try {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            writeField(body, boundary, "file", filename, contentType, audio);
            writeField(body, boundary, "model", null, "text/plain", model.getBytes(StandardCharsets.UTF_8));
            if (language != null) {
                writeField(body, boundary, "language", null, "text/plain", language.getBytes(StandardCharsets.UTF_8));
            }
            body.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            return http.postMultipart(
                    "v1/audio/transcriptions",
                    boundary,
                    body.toByteArray(),
                    TranscriptionResponse.class);
        } catch (IOException e) {
            throw new KugelAudioException("Failed to encode ASR upload", e);
        }
    }

    private static void writeField(
            ByteArrayOutputStream out,
            String boundary,
            String name,
            String filename,
            String contentType,
            byte[] data) throws IOException {
        String disposition = "Content-Disposition: form-data; name=\"" + name + "\""
                + (filename == null ? "" : "; filename=\"" + filename + "\"");
        out.write(("--" + boundary + "\r\n" + disposition + "\r\n"
                + "Content-Type: " + contentType + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(data);
        out.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }
}
