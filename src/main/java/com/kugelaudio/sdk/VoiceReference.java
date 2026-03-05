package com.kugelaudio.sdk;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A reference audio file attached to a voice.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class VoiceReference {

    private final int id;
    private final String filename;
    private final String referenceText;

    public VoiceReference(
            @JsonProperty("id") int id,
            @JsonProperty("filename") String filename,
            @JsonProperty("reference_text") String referenceText) {
        this.id = id;
        this.filename = filename;
        this.referenceText = referenceText;
    }

    public int getId() { return id; }
    public String getFilename() { return filename; }
    public String getReferenceText() { return referenceText; }

    @Override
    public String toString() {
        return "VoiceReference{id=" + id + ", filename='" + filename + "'}";
    }
}
