package com.kugelaudio.sdk;

import com.kugelaudio.sdk.internal.HttpHelper;

import java.util.List;

/**
 * Resource for listing available TTS models.
 *
 * <pre>{@code
 * List<Model> models = client.models().list();
 * models.forEach(m -> System.out.println(m.getId() + ": " + m.getName()));
 * }</pre>
 */
public final class ModelsResource {

    private final HttpHelper http;

    ModelsResource(HttpHelper http) {
        this.http = http;
    }

    /**
     * Lists all available TTS models.
     */
    public List<Model> list() {
        return http.getListFromEnvelope("v1/models", "models", Model.class);
    }
}
