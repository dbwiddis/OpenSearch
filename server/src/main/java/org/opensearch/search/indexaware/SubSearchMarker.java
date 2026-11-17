/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.search.indexaware;

import org.opensearch.common.util.concurrent.ThreadContext;

import java.util.Collection;
import java.util.Collections;

/**
 * Marks a search as a sub-search that {@code IndexAwareSearchRequestProcessor}s must not process again.
 * <p>
 * A processor's own output may be (or contain) a composite query that, when executed, issues further
 * internal searches &mdash; for example, a fused {@code hybrid} query's per-leg searches and its count
 * query. Those internal searches go through the same coordinator code path as any other search and would
 * otherwise be processed again, recursively. A plugin that issues such sub-searches itself must mark the
 * thread context before doing so, scoped to exactly that sub-search's duration, with {@link #markForSubSearch}.
 * The hook checks {@link #isMarked} and skips processing when it's set.
 * <p>
 * {@link #markForSubSearch} uses {@link ThreadContext#newStoredContext(boolean, boolean, Collection)}
 * to snapshot the context, add the marker, and restore the pre-marking transient state on close &mdash;
 * unlike {@link ThreadContext#stashContext()}, which replaces request headers, response headers, and every
 * other transient with a mostly-empty default context. This restores the <em>whole</em> prior transient
 * state (not literally one isolated key), while still preserving any response headers the sub-search
 * accumulated before closing.
 *
 * @opensearch.internal
 */
public final class SubSearchMarker {

    /** Thread-context transient key. Public because plugins outside this package need to set it. */
    public static final String SUB_SEARCH_TRANSIENT_NAME = "search_request_processor.sub_search";

    private SubSearchMarker() {}

    /**
     * Marks {@code threadContext} as a sub-search for the duration of the returned {@link ThreadContext.StoredContext}.
     * Use in a try-with-resources around issuing the sub-search:
     * <pre>{@code
     * try (ThreadContext.StoredContext ignored = SubSearchMarker.markForSubSearch(threadContext)) {
     *     // issue the sub-search
     * }
     * }</pre>
     * Closing the returned context restores the transient state to exactly what it was before marking
     * (including, for a search already inside a marked sub-search, staying marked) &mdash; while
     * preserving any response headers the sub-search accumulated in the meantime.
     * <p>
     * Tolerates {@link #SUB_SEARCH_TRANSIENT_NAME} already being set to something other than
     * {@link Boolean#TRUE} (the key is public; nothing stops another caller from having set it): only sets
     * it when the key is entirely absent, since {@link ThreadContext#putTransient} throws if a value,
     * any value, is already present for the key.
     */
    public static ThreadContext.StoredContext markForSubSearch(ThreadContext threadContext) {
        final ThreadContext.StoredContext restorable = threadContext.newStoredContext(true, false, Collections.emptyList());
        if (threadContext.getTransient(SUB_SEARCH_TRANSIENT_NAME) == null) {
            threadContext.putTransient(SUB_SEARCH_TRANSIENT_NAME, Boolean.TRUE);
        }
        return restorable;
    }

    /** Whether the current thread context is marked as a sub-search. */
    public static boolean isMarked(ThreadContext threadContext) {
        return Boolean.TRUE.equals(threadContext.getTransient(SUB_SEARCH_TRANSIENT_NAME));
    }
}
