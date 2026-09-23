package com.kugelaudio.sdk;

import com.fasterxml.jackson.core.type.TypeReference;
import com.kugelaudio.sdk.internal.HttpHelper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Resource for managing per-project custom word dictionaries.
 *
 * <p>Customers use this to sync pronunciation/replacement dictionaries
 * programmatically. The TTS-side cache is invalidated automatically after
 * every mutation, so the next synthesis request picks up the change.
 *
 * <pre>{@code
 * Dictionary brand = client.dictionaries().create("Brand names", null, null);
 * client.dictionaries().entries().add(
 *     brand.getId(),
 *     new DictionaryEntryInput("Postgres", "post-gres"));
 *
 * // Sync from external source — atomic, idempotent
 * client.dictionaries().entries().replaceAll(brand.getId(), List.of(
 *     new DictionaryEntryInput("Postgres", "post-gres"),
 *     new DictionaryEntryInput("Kubernetes", "koo-ber-net-eez")));
 * }</pre>
 */
public final class DictionariesResource {

    private final HttpHelper http;
    private final DictionaryEntriesResource entries;

    DictionariesResource(HttpHelper http) {
        this.http = http;
        this.entries = new DictionaryEntriesResource(http);
    }

    /** Per-entry operations within a dictionary. */
    public DictionaryEntriesResource entries() {
        return entries;
    }

    /** Lists every dictionary in the caller's project. */
    public List<Dictionary> list() {
        return list(null);
    }

    /** Lists dictionaries; master-key callers must supply a projectId. */
    public List<Dictionary> list(Long projectId) {
        String path = buildPath("v1/dictionaries", Map.of(), projectId);
        return http.getListFromEnvelope(path, "dictionaries", Dictionary.class);
    }

    /** Creates a new dictionary. */
    public Dictionary create(String name, String description, String language) {
        return create(name, description, language, null);
    }

    /** Creates a new dictionary; master-key callers must supply a projectId. */
    public Dictionary create(String name, String description, String language, Long projectId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        if (description != null) body.put("description", description);
        if (language != null) body.put("language", language);
        String path = buildPath("v1/dictionaries", Map.of(), projectId);
        return http.post(path, body, Dictionary.class);
    }

    /** Fetches a single dictionary by ID. */
    public Dictionary get(long dictionaryId) {
        return get(dictionaryId, null);
    }

    /** Fetches a single dictionary by ID with explicit projectId. */
    public Dictionary get(long dictionaryId, Long projectId) {
        String path = buildPath("v1/dictionaries/" + dictionaryId, Map.of(), projectId);
        return http.get(path, Dictionary.class);
    }

    /**
     * Updates a dictionary. Only non-null fields are sent.
     *
     * @param name        new name (or null to leave unchanged)
     * @param description new description (or null)
     * @param language    new BCP-47 language tag (or null)
     * @param isActive    new active flag (or null)
     */
    public Dictionary update(long dictionaryId, String name, String description,
                             String language, Boolean isActive) {
        return update(dictionaryId, name, description, language, isActive, null);
    }

    /** Updates a dictionary with explicit projectId. */
    public Dictionary update(long dictionaryId, String name, String description,
                             String language, Boolean isActive, Long projectId) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (name != null) body.put("name", name);
        if (description != null) body.put("description", description);
        if (language != null) body.put("language", language);
        if (isActive != null) body.put("is_active", isActive);
        String path = buildPath("v1/dictionaries/" + dictionaryId, Map.of(), projectId);
        return http.patch(path, body, Dictionary.class);
    }

    /** Deletes a dictionary (cascades to its entries). */
    public void delete(long dictionaryId) {
        delete(dictionaryId, null);
    }

    /** Deletes a dictionary with explicit projectId. */
    public void delete(long dictionaryId, Long projectId) {
        String path = buildPath("v1/dictionaries/" + dictionaryId, Map.of(), projectId);
        http.delete(path);
    }

    // ─── DictionaryEntriesResource ──────────────────────────────────────────

    /**
     * Resource for managing entries inside a single dictionary. Each method
     * takes a ``dictionaryId``.
     */
    public static final class DictionaryEntriesResource {

        private final HttpHelper http;

        DictionaryEntriesResource(HttpHelper http) {
            this.http = http;
        }

        /** List entries in the dictionary. */
        public DictionaryEntryListResponse list(long dictionaryId) {
            return list(dictionaryId, null, null, null, null);
        }

        /**
         * List entries with optional search + pagination.
         *
         * @param search    Case-insensitive substring on ``word`` (or null)
         * @param limit     Page size 1-500 (or null = 100)
         * @param offset    Pagination offset (or null = 0)
         * @param projectId Required only for master-key callers
         */
        public DictionaryEntryListResponse list(long dictionaryId, String search,
                                                 Integer limit, Integer offset,
                                                 Long projectId) {
            Map<String, Object> params = new LinkedHashMap<>();
            if (search != null) params.put("search", search);
            if (limit != null) params.put("limit", limit);
            if (offset != null) params.put("offset", offset);
            String path = buildPath(
                    "v1/dictionaries/" + dictionaryId + "/entries", params, projectId);
            return http.get(path, DictionaryEntryListResponse.class);
        }

        /** Add a single entry to a dictionary. */
        public DictionaryEntry add(long dictionaryId, DictionaryEntryInput entry) {
            return add(dictionaryId, entry, null);
        }

        /** Add a single entry with explicit projectId. */
        public DictionaryEntry add(long dictionaryId, DictionaryEntryInput entry, Long projectId) {
            Map<String, Object> body = entry.toMap();
            String path = buildPath(
                    "v1/dictionaries/" + dictionaryId + "/entries", Map.of(), projectId);
            return http.post(path, body, DictionaryEntry.class);
        }

        /** Update an existing entry. Non-null fields only. */
        public DictionaryEntry update(long dictionaryId, long entryId,
                                       String word, String replacement,
                                       String ipa, Boolean caseSensitive) {
            return update(dictionaryId, entryId, word, replacement, ipa, caseSensitive, null);
        }

        public DictionaryEntry update(long dictionaryId, long entryId,
                                       String word, String replacement,
                                       String ipa, Boolean caseSensitive,
                                       Long projectId) {
            Map<String, Object> body = new LinkedHashMap<>();
            if (word != null) body.put("word", word);
            if (replacement != null) body.put("replacement", replacement);
            if (ipa != null) body.put("ipa", ipa);
            if (caseSensitive != null) body.put("case_sensitive", caseSensitive);
            String path = buildPath(
                    "v1/dictionaries/" + dictionaryId + "/entries/" + entryId,
                    Map.of(), projectId);
            return http.patch(path, body, DictionaryEntry.class);
        }

        /** Delete a single entry. */
        public void delete(long dictionaryId, long entryId) {
            delete(dictionaryId, entryId, null);
        }

        public void delete(long dictionaryId, long entryId, Long projectId) {
            String path = buildPath(
                    "v1/dictionaries/" + dictionaryId + "/entries/" + entryId,
                    Map.of(), projectId);
            http.delete(path);
        }

        /**
         * Atomically replace every entry in the dictionary with ``entries``.
         * Entries currently in the dictionary whose word is not in the
         * supplied list are deleted. Idempotent — useful for syncing from
         * an external source.
         */
        public BulkReplaceResult replaceAll(long dictionaryId, List<DictionaryEntryInput> entries) {
            return replaceAll(dictionaryId, entries, null);
        }

        public BulkReplaceResult replaceAll(long dictionaryId,
                                             List<DictionaryEntryInput> entries,
                                             Long projectId) {
            List<Map<String, Object>> payload = new ArrayList<>(entries.size());
            for (DictionaryEntryInput e : entries) {
                payload.add(e.toMap());
            }
            Map<String, Object> body = Map.of("entries", payload);
            String path = buildPath(
                    "v1/dictionaries/" + dictionaryId + "/entries", Map.of(), projectId);
            return http.put(path, body, BulkReplaceResult.class);
        }
    }

    private static String buildPath(String base, Map<String, Object> params, Long projectId) {
        StringBuilder sb = new StringBuilder(base);
        String sep = "?";
        for (Map.Entry<String, Object> e : params.entrySet()) {
            if (e.getValue() == null) continue;
            sb.append(sep).append(e.getKey()).append("=")
              .append(java.net.URLEncoder.encode(
                      String.valueOf(e.getValue()), java.nio.charset.StandardCharsets.UTF_8));
            sep = "&";
        }
        if (projectId != null) {
            sb.append(sep).append("project_id=").append(projectId);
        }
        return sb.toString();
    }
}
