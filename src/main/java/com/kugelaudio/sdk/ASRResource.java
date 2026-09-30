package com.kugelaudio.sdk;

import com.kugelaudio.sdk.internal.HttpHelper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
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
        return transcribe(audio, filename, contentType, language, model, List.of());
    }

    /**
     * Transcribe one recording with your vocabulary for this request.
     *
     * @param boostedPhrases names, brands and domain terms to spell as written;
     *     the server refuses a list over its limits instead of shortening it
     */
    public TranscriptionResponse transcribe(
            byte[] audio,
            String filename,
            String contentType,
            String language,
            String model,
            List<String> boostedPhrases) {
        if (audio == null || audio.length == 0) {
            throw new ValidationException("ASR audio must not be empty");
        }
        if (!MODEL_ID.equals(model)) {
            throw new ValidationException("Unsupported ASR model; use " + MODEL_ID);
        }
        if (boostedPhrases == null) {
            throw new ValidationException("boostedPhrases must not be null; pass an empty list");
        }
        String boundary = UUID.randomUUID().toString();
        try {
            return http.postMultipart(
                    "v1/audio/transcriptions",
                    boundary,
                    encodeUpload(boundary, audio, filename, contentType, language, model, boostedPhrases),
                    TranscriptionResponse.class);
        } catch (IOException e) {
            throw new KugelAudioException("Failed to encode ASR upload", e);
        }
    }

    /** The multipart body: one {@code boosted_phrases} field per phrase. */
    static byte[] encodeUpload(
            String boundary,
            byte[] audio,
            String filename,
            String contentType,
            String language,
            String model,
            List<String> boostedPhrases) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        writeField(body, boundary, "file", filename, contentType, audio);
        writeField(body, boundary, "model", null, "text/plain", model.getBytes(StandardCharsets.UTF_8));
        if (language != null) {
            writeField(body, boundary, "language", null, "text/plain", language.getBytes(StandardCharsets.UTF_8));
        }
        for (String phrase : boostedPhrases) {
            writeField(body, boundary, "boosted_phrases", null, "text/plain", phrase.getBytes(StandardCharsets.UTF_8));
        }
        body.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return body.toByteArray();
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
