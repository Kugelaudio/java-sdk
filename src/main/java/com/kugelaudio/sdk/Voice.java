package com.kugelaudio.sdk;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Voice metadata returned by the API.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class Voice {

    private final int id;
    private final String name;
    private final String sex;
    private final String language;
    private final String sampleUrl;
    private final boolean isPublic;

    public Voice(
            @JsonProperty("id") int id,
            @JsonProperty("name") String name,
            @JsonProperty("sex") String sex,
            @JsonProperty("language") String language,
            @JsonProperty("sample_url") String sampleUrl,
            @JsonProperty("is_public") boolean isPublic) {
        this.id = id;
        this.name = name;
        this.sex = sex;
        this.language = language;
        this.sampleUrl = sampleUrl;
        this.isPublic = isPublic;
    }

    public int getId() { return id; }
    public String getName() { return name; }
    public String getSex() { return sex; }
    public String getLanguage() { return language; }
    public String getSampleUrl() { return sampleUrl; }
    public boolean isPublic() { return isPublic; }

    @Override
    public String toString() {
        return "Voice{id=" + id + ", name='" + name + "', language='" + language + "'}";
    }
}
