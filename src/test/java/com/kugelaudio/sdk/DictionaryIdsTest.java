package com.kugelaudio.sdk;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Per-request dictionary selection (KUG-1094) on the three request/config
 * builders. Contract: {@code null} (not set) = project default (all active
 * dictionaries), empty list = explicit opt-out, list = exactly those
 * dictionaries.
 */
class DictionaryIdsTest {

    @Test
    void generateRequestEmptyListIsPreserved() {
        // [] is the explicit opt-out and must stay distinguishable from null.
        GenerateRequest request = GenerateRequest.builder("Hi")
                .dictionaryIds(List.of())
                .build();
        assertNotNull(request.getDictionaryIds());
        assertTrue(request.getDictionaryIds().isEmpty());
    }

    @Test
    void generateRequestCopiesCallerList() {
        List<Integer> ids = new ArrayList<>(List.of(1));
        GenerateRequest request = GenerateRequest.builder("Hi")
                .dictionaryIds(ids)
                .build();
        ids.add(2);
        assertEquals(List.of(1), request.getDictionaryIds());
        assertThrows(UnsupportedOperationException.class,
                () -> request.getDictionaryIds().add(3));
    }

}
