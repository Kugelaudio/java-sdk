package com.kugelaudio.sdk;

import com.kugelaudio.sdk.internal.HttpHelper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * Speech-to-text uploads through public KugelAudio ingress.
 *
 * <p>{@code model} is the speech-to-text model ID, sent as given; pass
 * {@code null} to leave it out and use the API's default model.
 */
public final class ASRResource {
    private final HttpHelper http;

    ASRResource(HttpHelper http) {
        this.http = http;
    }

    public TranscriptionResponse transcribe(byte[] audio) {
        return transcribe(audio, "audio.wav", "audio/wav", null, null);
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
        return transcribe(audio, filename, contentType, language, model, boostedPhrases, null, null);
    }

    /**
     * Transcribe one recording with your vocabulary and a dictionary selection.
     *
     * @param boostedPhrases names, brands and domain terms to spell as written;
     *     the server refuses a list over its limits instead of shortening it
     * @param projectId the project whose dictionaries apply; {@code null} uses
     *     the API key's project
     * @param dictionaryIds the project dictionaries whose words are applied as
     *     phrases after {@code boostedPhrases}; {@code null} applies every active
     *     dictionary of the project; an empty list applies none.
     */
    public TranscriptionResponse transcribe(
            byte[] audio,
            String filename,
            String contentType,
            String language,
            String model,
            List<String> boostedPhrases,
            Long projectId,
            List<Integer> dictionaryIds) {
        if (audio == null || audio.length == 0) {
            throw new ValidationException("ASR audio must not be empty");
        }
        if (boostedPhrases == null) {
            throw new ValidationException("boostedPhrases must not be null; pass an empty list");
        }
        String boundary = UUID.randomUUID().toString();
        try {
            return http.postMultipart(
                    "v1/audio/transcriptions",
                    boundary,
                    encodeUpload(boundary, audio, filename, contentType, language, model, boostedPhrases,
                            projectId, dictionaryIds),
                    TranscriptionResponse.class);
        } catch (IOException e) {
            throw new KugelAudioException("Failed to encode ASR upload", e);
        }
    }

    /**
     * The multipart body: a {@code model} field only when a model is given, and
     * one {@code boosted_phrases} field per phrase, a {@code project_id} field
     * only when a project is given, and one {@code dictionary_ids} field per id.
     */
    static byte[] encodeUpload(
            String boundary,
            byte[] audio,
            String filename,
            String contentType,
            String language,
            String model,
            List<String> boostedPhrases,
            Long projectId,
            List<Integer> dictionaryIds) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        writeField(body, boundary, "file", filename, contentType, audio);
        if (model != null) {
            writeField(body, boundary, "model", null, "text/plain", model.getBytes(StandardCharsets.UTF_8));
        }
        if (language != null) {
            writeField(body, boundary, "language", null, "text/plain", language.getBytes(StandardCharsets.UTF_8));
        }
        for (String phrase : boostedPhrases) {
            writeField(body, boundary, "boosted_phrases", null, "text/plain", phrase.getBytes(StandardCharsets.UTF_8));
        }
        if (projectId != null) {
            writeField(body, boundary, "project_id", null, "text/plain",
                    projectId.toString().getBytes(StandardCharsets.UTF_8));
        }
        if (dictionaryIds != null && dictionaryIds.isEmpty()) {
            // Repeated form fields cannot carry an empty list: one empty
            // dictionary_ids field is the multipart spelling of [].
            writeField(body, boundary, "dictionary_ids", null, "text/plain", new byte[0]);
        } else if (dictionaryIds != null) {
            for (Integer dictionaryId : dictionaryIds) {
                writeField(body, boundary, "dictionary_ids", null, "text/plain",
                        dictionaryId.toString().getBytes(StandardCharsets.UTF_8));
            }
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
