package com.kugelaudio.sdk;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kugelaudio.sdk.internal.HttpHelper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Resource for managing voices (CRUD, references, publishing).
 *
 * <pre>{@code
 * VoiceListResponse response = client.voices().list();
 * VoiceDetail detail = client.voices().get(123);
 * }</pre>
 */
public final class VoicesResource {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final HttpHelper http;

    VoicesResource(HttpHelper http) {
        this.http = http;
    }

    /** Lists voices with optional filters. */
    public VoiceListResponse list() {
        return list(null, null, null, null);
    }

    /** Lists voices with optional filters. */
    public VoiceListResponse list(String language, Boolean includePublic, Integer limit, Integer offset) {
        StringBuilder path = new StringBuilder("v1/voices");
        String sep = "?";
        if (language != null) { path.append(sep).append("language=").append(language); sep = "&"; }
        if (includePublic != null) { path.append(sep).append("include_public=").append(includePublic); sep = "&"; }
        if (limit != null) { path.append(sep).append("limit=").append(limit); sep = "&"; }
        if (offset != null) { path.append(sep).append("offset=").append(offset); }
        return http.get(path.toString(), VoiceListResponse.class);
    }

    /** Gets detailed voice information by ID. */
    public VoiceDetail get(int voiceId) {
        return http.get("v1/voices/" + voiceId, VoiceDetail.class);
    }

    /**
     * Creates a new voice with reference audio files.
     *
     * @param name     Voice name
     * @param sex      "male" or "female"
     * @param language Language code (e.g. "en", "de"), sent as the voice's only supported language;
     *                 {@code null} leaves the server default ({@code ["en"]})
     * @param files    Reference audio file paths
     */
    public VoiceDetail create(String name, String sex, String language, List<Path> files) {
        String boundary = UUID.randomUUID().toString();
        try {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("name", name);
            metadata.put("sex", sex);
            if (language != null) {
                metadata.put("supported_languages", List.of(language));
            }

            writeMultipartField(body, boundary, "metadata", null, "application/json", MAPPER.writeValueAsBytes(metadata));

            if (files != null) {
                for (Path file : files) {
                    byte[] fileBytes = Files.readAllBytes(file);
                    writeMultipartField(body, boundary, "files", file.getFileName().toString(), "audio/wav", fileBytes);
                }
            }
            body.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            return http.postMultipart("v1/voices", boundary, body.toByteArray(), VoiceDetail.class);
        } catch (IOException e) {
            throw new KugelAudioException("Failed to create voice: " + e.getMessage(), e);
        }
    }

    /** Updates voice properties. */
    public VoiceDetail update(int voiceId, Map<String, Object> updates) {
        return http.patch("v1/voices/" + voiceId, updates, VoiceDetail.class);
    }

    /** Deletes a voice. */
    public void delete(int voiceId) {
        http.delete("v1/voices/" + voiceId);
    }

    /** Lists reference audio files for a voice. */
    public List<VoiceReference> listReferences(int voiceId) {
        return http.getList("v1/voices/" + voiceId + "/references", new TypeReference<>() {});
    }

    /**
     * Adds a reference audio file to a voice.
     *
     * @param voiceId       Voice ID
     * @param file          Audio file path
     * @param referenceText Optional transcript of the reference audio
     */
    public VoiceReference addReference(int voiceId, Path file, String referenceText) {
        String boundary = UUID.randomUUID().toString();
        try {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            byte[] fileBytes = Files.readAllBytes(file);
            writeMultipartField(body, boundary, "file", file.getFileName().toString(), "audio/wav", fileBytes);
            if (referenceText != null) {
                writeMultipartField(body, boundary, "reference_text", null, "text/plain", referenceText.getBytes(StandardCharsets.UTF_8));
            }
            body.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            return http.postMultipart("v1/voices/" + voiceId + "/references", boundary, body.toByteArray(), VoiceReference.class);
        } catch (IOException e) {
            throw new KugelAudioException("Failed to add reference: " + e.getMessage(), e);
        }
    }

    /** Deletes a reference audio file from a voice. */
    public void deleteReference(int voiceId, int referenceId) {
        http.delete("v1/voices/" + voiceId + "/references/" + referenceId);
    }

    /** Requests publication of a voice to the public library. */
    public VoiceDetail publish(int voiceId) {
        return http.post("v1/voices/" + voiceId + "/publish", null, VoiceDetail.class);
    }

    /**
     * Triggers server-side sample generation for a voice. Only {@link VoiceDetail#getSampleUrl()}
     * is populated; the response carries no other voice fields.
     */
    public VoiceDetail generateSample(int voiceId) {
        return http.post("v1/voices/" + voiceId + "/generate-sample", null, VoiceDetail.class);
    }

    private static void writeMultipartField(ByteArrayOutputStream out, String boundary,
                                             String name, String filename, String contentType, byte[] data) throws IOException {
        StringBuilder header = new StringBuilder();
        header.append("--").append(boundary).append("\r\n");
        header.append("Content-Disposition: form-data; name=\"").append(name).append("\"");
        if (filename != null) {
            header.append("; filename=\"").append(filename).append("\"");
        }
        header.append("\r\n");
        if (contentType != null) {
            header.append("Content-Type: ").append(contentType).append("\r\n");
        }
        header.append("\r\n");
        out.write(header.toString().getBytes(StandardCharsets.UTF_8));
        out.write(data);
        out.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }
}
