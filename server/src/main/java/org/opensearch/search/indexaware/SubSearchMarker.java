/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.search.indexaware;

import org.opensearch.common.util.concurrent.ThreadContext;

/**
 * Marks a search as a sub-search that {@link IndexAwareSearchRequestProcessor}s must not process again.
 * <p>
 * A processor's own output may be (or contain) a composite query that, when executed, issues further
 * internal searches \u2014 for example, a fused {@code hybrid} query's per-leg searches and its count query.
 * Those internal searches go through the same coordinator code path as any other search and would
 * otherwise be processed again, recursively. A plugin that issues such sub-searches itself must mark
 * the thread context before doing so, with {@link #mark}; the hook checks {@link #isMarked} and skips
 * processing when it's set.
 * <p>
 * v1 is a single boolean marker for the current thread context (inherited by child contexts), not a
 * per-search-id registry: it is set for the duration of issuing and running one internal search and
 * should be cleared (e.g. with a try/finally around {@link ThreadContext#stashContext()}) once that
 * search completes, so it doesn't leak into unrelated searches on the same thread.
 *
 * @opensearch.experimental
 */
public final class SubSearchMarker {

    /** Thread-context transient key. Package-private visibility would be preferable; public because
     *  plugins outside this package need to set it. */
    public static final String SUB_SEARCH_TRANSIENT_NAME = "search_request_processor.sub_search";

    private SubSearchMarker() {}

    /** Mark the current thread context so {@link #isMarked} returns {@code true} for any search issued on it. */
    public static void mark(ThreadContext threadContext) {
        if (!Boolean.TRUE.equals(threadContext.getTransient(SUB_SEARCH_TRANSIENT_NAME))) {
            threadContext.putTransient(SUB_SEARCH_TRANSIENT_NAME, Boolean.TRUE);
        }
    }

    /** Whether the current thread context is marked as a sub-search. */
    public static boolean isMarked(ThreadContext threadContext) {
        return Boolean.TRUE.equals(threadContext.getTransient(SUB_SEARCH_TRANSIENT_NAME));
    }
}
