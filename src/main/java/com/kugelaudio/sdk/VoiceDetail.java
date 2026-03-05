package com.kugelaudio.sdk;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Detailed voice information including references.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class VoiceDetail {

    private final int id;
    private final String name;
    private final String sex;
    private final String language;
    private final String sampleUrl;
    private final boolean isPublic;
    private final List<VoiceReference> references;

    public VoiceDetail(
            @JsonProperty("id") int id,
            @JsonProperty("name") String name,
            @JsonProperty("sex") String sex,
            @JsonProperty("language") String language,
            @JsonProperty("sample_url") String sampleUrl,
            @JsonProperty("is_public") boolean isPublic,
            @JsonProperty("references") List<VoiceReference> references) {
        this.id = id;
        this.name = name;
        this.sex = sex;
        this.language = language;
        this.sampleUrl = sampleUrl;
        this.isPublic = isPublic;
        this.references = references != null ? List.copyOf(references) : List.of();
    }

    public int getId() { return id; }
    public String getName() { return name; }
    public String getSex() { return sex; }
    public String getLanguage() { return language; }
    public String getSampleUrl() { return sampleUrl; }
    public boolean isPublic() { return isPublic; }
    public List<VoiceReference> getReferences() { return references; }

    @Override
    public String toString() {
        return "VoiceDetail{id=" + id + ", name='" + name + "', refs=" + references.size() + "}";
    }
}
