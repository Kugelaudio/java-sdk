package com.kugelaudio.sdk;

/**
 * Deployment region controlling which API endpoint the SDK connects to.
 *
 * <p>Use {@link #EU} to select the direct EU endpoint,
 * {@code api.eu.kugelaudio.com}. If no region is selected, the SDK uses
 * {@code api.kugelaudio.com}.
 */
public enum Region {
    EU("https://api.eu.kugelaudio.com"),
    US("https://api.kugelaudio.com"),
    GLOBAL("https://api.kugelaudio.com");

    private final String url;

    Region(String url) {
        this.url = url;
    }

    /** Returns the base URL for this region. */
    public String getUrl() {
        return url;
    }
}
