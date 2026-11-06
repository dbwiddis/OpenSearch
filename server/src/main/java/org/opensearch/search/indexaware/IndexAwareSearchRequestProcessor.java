/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.search.indexaware;

import org.opensearch.search.builder.SearchSourceBuilder;

/**
 * Extension point for plugins to act on a search request with per-index mapping context, before the
 * coordinator query rewrite \u2014 the same kind of thing a search pipeline request processor does, but
 * driven by index and field configuration rather than by a pipeline attached to the request.
 * <p>
 * Registered via {@link org.opensearch.plugins.SearchPlugin#getIndexAwareSearchRequestProcessors()}. Each
 * registered processor runs once per request, on the coordinator node, after any search pipeline request
 * processors have run and before the normal coordinator query rewrite ({@code Rewriteable.rewrite}) \u2014 so
 * a processor's output (for example a composite query such as {@code hybrid}) still fans out and rewrites
 * normally afterward.
 * <p>
 * Unlike a search pipeline request processor, this hook:
 * <ul>
 *   <li>sees the concrete local indices this request targets and their current {@code IndexMetadata}
 *       (including mappings), via {@link IndexAwareContext}, so it can be correct per index
 *       (for example across an alias where one index has a feature mapped and another doesn't);</li>
 *   <li>always runs, regardless of which (or no) search pipeline is attached to the request, and can't
 *       be bypassed with {@code ?search_pipeline=_none}.</li>
 * </ul>
 * Not every implementation rewrites the query: a processor may instead act purely on {@code size},
 * {@code from}, aggregations, or other source fields, the same way some pipeline request processors do
 * today \u2014 {@code process} just happens to need to return the (possibly unchanged) source either way.
 * <p>
 * This is a v1, synchronous-only hook: a processor that needs to make an asynchronous call (such as
 * resolving an embedding model) must block for it. A finalize hook and de-duplicated async resolution
 * are proposed as a Phase 2 follow-on; see the companion RFC
 * (opensearch-project/OpenSearch, "Index-aware coordinator rewrite and search request hooks").
 * <p>
 * <b>Re-entrancy.</b> A processor's own output may cause OpenSearch to issue further internal searches
 * (for example, a fused {@code hybrid} query's per-leg searches). Those internal searches go through
 * the same coordinator code path and would otherwise be processed again, recursively. A processor that
 * issues such sub-searches itself is responsible for marking them so this hook skips them \u2014 see
 * {@link SubSearchMarker}. A lesson from an internal proof of concept: skipping this for an early,
 * "simple" processor is not safe to defer, because any composite query a processor produces can trigger
 * unbounded recursion (observed as a {@code StackOverflowError} without the marker).
 *
 * @opensearch.experimental
 */
public interface IndexAwareSearchRequestProcessor {

    /**
     * Act on a search request's source.
     *
     * @param source  the search request's source, after any search pipeline request processors have run
     * @param context resolved local target indices and their metadata (including current mappings)
     * @return the (possibly modified) source, or {@code source} unchanged if this processor doesn't apply to this request
     */
    SearchSourceBuilder process(SearchSourceBuilder source, IndexAwareContext context);
}
