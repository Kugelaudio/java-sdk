package com.kugelaudio.sdk;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Counts returned by ``entries.replaceAll``. */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class BulkReplaceResult {

    private final int upserted;
    private final int deleted;
    private final int total;

    public BulkReplaceResult(
            @JsonProperty("upserted") int upserted,
            @JsonProperty("deleted") int deleted,
            @JsonProperty("total") int total) {
        this.upserted = upserted;
        this.deleted = deleted;
        this.total = total;
    }

    public int getUpserted() { return upserted; }
    public int getDeleted() { return deleted; }
    public int getTotal() { return total; }
}
