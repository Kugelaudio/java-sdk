package com.kugelaudio.sdk;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A single word → replacement / IPA mapping within a dictionary.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class DictionaryEntry {

    private final long id;
    private final long dictionaryId;
    private final String word;
    private final String replacement;
    private final String ipa;
    private final boolean caseSensitive;
    private final String createdAt;
    private final String updatedAt;

    public DictionaryEntry(
            @JsonProperty("id") long id,
            @JsonProperty("dictionary_id") long dictionaryId,
            @JsonProperty("word") String word,
            @JsonProperty("replacement") String replacement,
            @JsonProperty("ipa") String ipa,
            @JsonProperty("case_sensitive") boolean caseSensitive,
            @JsonProperty("created_at") String createdAt,
            @JsonProperty("updated_at") String updatedAt) {
        this.id = id;
        this.dictionaryId = dictionaryId;
        this.word = word;
        this.replacement = replacement;
        this.ipa = ipa;
        this.caseSensitive = caseSensitive;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public long getId() { return id; }
    public long getDictionaryId() { return dictionaryId; }
    public String getWord() { return word; }
    public String getReplacement() { return replacement; }
    public String getIpa() { return ipa; }
    public boolean isCaseSensitive() { return caseSensitive; }
    public String getCreatedAt() { return createdAt; }
    public String getUpdatedAt() { return updatedAt; }

    @Override
    public String toString() {
        return "DictionaryEntry{id=" + id + ", word='" + word
                + "', replacement='" + replacement + "'}";
    }
}
