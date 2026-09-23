package com.kugelaudio.sdk;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Payload for creating or replacing a single dictionary entry.
 *
 * <p>``word`` and ``replacement`` are required; ``ipa`` and
 * ``caseSensitive`` are optional.
 */
public final class DictionaryEntryInput {

    private final String word;
    private final String replacement;
    private final String ipa;
    private final Boolean caseSensitive;

    public DictionaryEntryInput(String word, String replacement) {
        this(word, replacement, null, null);
    }

    public DictionaryEntryInput(String word, String replacement, String ipa, Boolean caseSensitive) {
        if (word == null || word.isEmpty()) {
            throw new ValidationException("word is required");
        }
        if (replacement == null || replacement.isEmpty()) {
            throw new ValidationException("replacement is required");
        }
        this.word = word;
        this.replacement = replacement;
        this.ipa = ipa;
        this.caseSensitive = caseSensitive;
    }

    public String getWord() { return word; }
    public String getReplacement() { return replacement; }
    public String getIpa() { return ipa; }
    public Boolean getCaseSensitive() { return caseSensitive; }

    /** Serialise to the snake_case shape the API expects. */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("word", word);
        map.put("replacement", replacement);
        if (ipa != null) map.put("ipa", ipa);
        if (caseSensitive != null) map.put("case_sensitive", caseSensitive);
        return map;
    }
}
