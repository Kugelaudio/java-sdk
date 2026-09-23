package com.kugelaudio.sdk;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** Paginated response from listing dictionary entries. */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class DictionaryEntryListResponse {

    private final List<DictionaryEntry> entries;
    private final int total;
    private final int limit;
    private final int offset;

    public DictionaryEntryListResponse(
            @JsonProperty("entries") List<DictionaryEntry> entries,
            @JsonProperty("total") int total,
            @JsonProperty("limit") int limit,
            @JsonProperty("offset") int offset) {
        this.entries = entries;
        this.total = total;
        this.limit = limit;
        this.offset = offset;
    }

    public List<DictionaryEntry> getEntries() { return entries; }
    public int getTotal() { return total; }
    public int getLimit() { return limit; }
    public int getOffset() { return offset; }
}
