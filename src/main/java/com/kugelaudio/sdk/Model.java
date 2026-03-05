package com.kugelaudio.sdk;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * TTS model metadata returned by the API.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class Model {

    private final String id;
    private final String name;
    private final String description;
    private final String parameters;
    private final int maxInputLength;
    private final int sampleRate;

    public Model(
            @JsonProperty("id") String id,
            @JsonProperty("name") String name,
            @JsonProperty("description") String description,
            @JsonProperty("parameters") String parameters,
            @JsonProperty("max_input_length") int maxInputLength,
            @JsonProperty("sample_rate") int sampleRate) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.parameters = parameters;
        this.maxInputLength = maxInputLength;
        this.sampleRate = sampleRate;
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public String getParameters() { return parameters; }
    public int getMaxInputLength() { return maxInputLength; }
    public int getSampleRate() { return sampleRate; }

    @Override
    public String toString() {
        return "Model{id='" + id + "', name='" + name + "'}";
    }
}
