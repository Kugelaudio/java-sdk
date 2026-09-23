package com.kugelaudio.sdk;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Paginated response from the voices list endpoint.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class VoiceListResponse {
    private final List<Voice> voices;
    private final int total;
    private final int limit;
    private final int offset;

    public VoiceListResponse(
            @JsonProperty("voices") List<Voice> voices,
            @JsonProperty("total") int total,
            @JsonProperty("limit") int limit,
            @JsonProperty("offset") int offset) {
        this.voices = voices;
        this.total = total;
        this.limit = limit;
        this.offset = offset;
    }

    public List<Voice> getVoices() { return voices; }
    public int getTotal() { return total; }
    public int getLimit() { return limit; }
    public int getOffset() { return offset; }

    @Override
    public String toString() {
        return "VoiceListResponse{total=" + total + ", limit=" + limit + ", offset=" + offset + ", voices=" + voices.size() + "}";
    }
}
