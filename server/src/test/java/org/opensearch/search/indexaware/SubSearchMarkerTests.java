/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.search.indexaware;

import org.opensearch.common.settings.Settings;
import org.opensearch.common.util.concurrent.ThreadContext;
import org.opensearch.test.OpenSearchTestCase;

public class SubSearchMarkerTests extends OpenSearchTestCase {

    public void testNotMarkedByDefault() {
        ThreadContext threadContext = new ThreadContext(Settings.EMPTY);
        assertFalse(SubSearchMarker.isMarked(threadContext));
    }

    public void testMarkedForTheDurationOfTheSubSearch() {
        ThreadContext threadContext = new ThreadContext(Settings.EMPTY);
        try (ThreadContext.StoredContext ignored = SubSearchMarker.markForSubSearch(threadContext)) {
            assertTrue(SubSearchMarker.isMarked(threadContext));
        }
        assertFalse(SubSearchMarker.isMarked(threadContext));
    }

    public void testRestoreDoesNotClobberUnrelatedTransientValues() {
        // The gap this fixes: ThreadContext#stashContext() resets the whole context -- request headers,
        // response headers, and every other transient value a caller may have set -- not just the marker.
        ThreadContext threadContext = new ThreadContext(Settings.EMPTY);
        threadContext.putTransient("unrelated", "value");

        try (ThreadContext.StoredContext ignored = SubSearchMarker.markForSubSearch(threadContext)) {
            assertEquals("value", threadContext.<String>getTransient("unrelated"));
        }

        assertEquals("value", threadContext.<String>getTransient("unrelated"));
        assertFalse(SubSearchMarker.isMarked(threadContext));
    }

    public void testRestorePreservesResponseHeadersAddedDuringTheSubSearch() {
        ThreadContext threadContext = new ThreadContext(Settings.EMPTY);

        try (ThreadContext.StoredContext ignored = SubSearchMarker.markForSubSearch(threadContext)) {
            threadContext.addResponseHeader("sub-search-header", "value");
        }

        assertEquals("value", threadContext.getResponseHeaders().get("sub-search-header").get(0));
        assertFalse(SubSearchMarker.isMarked(threadContext));
    }

    public void testNestedMarkingStaysMarkedUntilTheOutermostScopeCloses() {
        ThreadContext threadContext = new ThreadContext(Settings.EMPTY);

        try (ThreadContext.StoredContext outer = SubSearchMarker.markForSubSearch(threadContext)) {
            assertTrue(SubSearchMarker.isMarked(threadContext));
            try (ThreadContext.StoredContext inner = SubSearchMarker.markForSubSearch(threadContext)) {
                assertTrue(SubSearchMarker.isMarked(threadContext));
            }
            // Closing the inner (already-marked) scope must not un-mark the outer scope.
            assertTrue(SubSearchMarker.isMarked(threadContext));
        }
        assertFalse(SubSearchMarker.isMarked(threadContext));
    }
}
