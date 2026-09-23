package com.kugelaudio.sdk;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A per-project pronunciation dictionary.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class Dictionary {

    private final long id;
    private final long projectId;
    private final String name;
    private final String description;
    private final String language;
    private final boolean isActive;
    private final String createdAt;
    private final String updatedAt;

    public Dictionary(
            @JsonProperty("id") long id,
            @JsonProperty("project_id") long projectId,
            @JsonProperty("name") String name,
            @JsonProperty("description") String description,
            @JsonProperty("language") String language,
            @JsonProperty("is_active") boolean isActive,
            @JsonProperty("created_at") String createdAt,
            @JsonProperty("updated_at") String updatedAt) {
        this.id = id;
        this.projectId = projectId;
        this.name = name;
        this.description = description;
        this.language = language;
        this.isActive = isActive;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public long getId() { return id; }
    public long getProjectId() { return projectId; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public String getLanguage() { return language; }
    public boolean isActive() { return isActive; }
    public String getCreatedAt() { return createdAt; }
    public String getUpdatedAt() { return updatedAt; }

    @Override
    public String toString() {
        return "Dictionary{id=" + id + ", projectId=" + projectId
                + ", name='" + name + "', isActive=" + isActive + "}";
    }
}
