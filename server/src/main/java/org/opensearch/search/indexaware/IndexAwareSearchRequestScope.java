/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.search.indexaware;

import org.opensearch.action.OriginalIndices;
import org.opensearch.cluster.metadata.IndexMetadata;
import org.opensearch.common.Nullable;
import org.opensearch.index.reindex.BulkByScrollTask;
import org.opensearch.tasks.Task;

import java.util.Collection;
import java.util.Map;

/**
 * Decides whether an {@code IndexAwareSearchRequestProcessor} should run for a given search request at all:
 * {@link #isInternalByDefault} classifies searches whose rewrite isn't safe to apply by default (part of a
 * job that reuses this search's resolution for later batches, or targeting an index user-facing rewrite
 * logic isn't meant to touch), and {@link #hasRemoteIndices} flags cross-cluster searches this hook's
 * local-only resolution can't safely act on.
 * <p>
 * A processor that still wants to run for an internal search must opt in; see
 * {@code IndexAwareSearchRequestProcessor#appliesToInternalSearches()}.
 *
 * @opensearch.internal
 */
public final class IndexAwareSearchRequestScope {

    private IndexAwareSearchRequestScope() {}

    /**
     * Whether {@code parentTask} or {@code targetIndices} make this search internal by default.
     * <p>
     * {@code parentTask} is internal if it's a {@link BulkByScrollTask} &mdash; the shared task type behind
     * {@code _delete_by_query}, {@code _update_by_query}, and {@code _reindex} (any present or future
     * subclass of {@code AbstractBulkByScrollRequest}, via its single {@code createTask()} override). Those
     * jobs issue an initial search whose resolved shard contexts are reused by later batches, so a rewrite
     * that changes which documents match would change which documents the job deletes/updates, not just how
     * one response is ranked.
     * <p>
     * {@code targetIndices} are internal if any of them is a system index ({@link IndexMetadata#isSystem()}):
     * core and plugin housekeeping reads of their own internal indices shouldn't be subject to user-facing
     * per-field rewrite logic.
     * <p>
     * Known limitation: this is an all-or-nothing decision for the whole request, not a per-index one. A
     * wildcard or alias search that happens to match one system index among several ordinary ones skips the
     * processors for every index in the request, not just the system one &mdash; splitting that apart needs
     * the same per-index-scoped rewrite context the companion RFC's mixed-alias gap (Martin Gaievski's
     * review) is still deciding between a core-side fix and a query-builder-side one. Not addressed here.
     * <p>
     * {@code _count} is not treated as internal: it runs through the same coordinator path as an ordinary
     * search (with {@code size: 0}), under the user's own top-level task, with no
     * {@link BulkByScrollTask} parent. A processor's rewrite applies to it exactly as it would to a
     * {@code _search} for the same query, which is the intended behavior, not an oversight.
     * <p>
     * This check is deliberately core-only: it can't know about a plugin-defined task type with the same
     * batch-reuse concern as {@link BulkByScrollTask} (for example, an async search plugin's own internal
     * re-searches), because core doesn't depend on that plugin's code. A cross-plugin marker for that case is
     * proposed, not yet implemented; see the companion RFC
     * (opensearch-project/OpenSearch#23569).
     *
     * @param parentTask    the task that issued this search, or {@code null} if it has none (or the parent
     *                      isn't tracked locally, e.g. it's on a different node)
     * @param targetIndices this search's resolved local target indices' metadata
     */
    public static boolean isInternalByDefault(@Nullable Task parentTask, Collection<IndexMetadata> targetIndices) {
        if (parentTask instanceof BulkByScrollTask) {
            return true;
        }
        for (IndexMetadata indexMetadata : targetIndices) {
            if (indexMetadata.isSystem()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether {@code remoteIndices} has any remote-cluster index: if so, this hook must not run at all for
     * the request, regardless of {@code ccs_minimize_roundtrips}.
     * <p>
     * An earlier version of this check only bailed out when {@code ccs_minimize_roundtrips} was {@code
     * false}, on the assumption that {@code true} (the default) means each remote cluster runs its own
     * complete, independent search &mdash; including its own hook invocation against its own mappings. That
     * assumption is wrong: {@code TransportSearchAction#ccsRemoteReduce} builds the request it sends to a
     * single remote cluster via {@code SearchRequest.subSearchRequest}, whose private constructor does
     * {@code this.source = searchRequest.source}, copying this local coordinator's already-rewritten source
     * as-is. The remote cluster's own hook then runs a second time, on the local rewrite's output, not on
     * the client's original query. A two-cluster integration test confirmed this: with the opted-in local
     * index and a non-opted-in remote index, a {@code match} search under {@code ccs_minimize_roundtrips=true}
     * (the default) returned hits as if <em>both</em> sides had been rewritten, instead of just the local
     * one. {@code ccs_minimize_roundtrips=false} and PIT/scroll/DFS/collapse-with-inner-hits requests (which
     * {@code TransportSearchAction#shouldMinimizeRoundtrips} forces onto the non-minimized path even when
     * {@code isCcsMinimizeRoundtrips()} is {@code true}) have the same bug by a more direct route: the
     * rewritten source goes straight to remote shards.
     * <p>
     * So this hook can't safely act on a request with any remote indices, under any mode: mirrors
     * {@code NeuralQueryBuilder}'s own existing policy for its per-field resolution, which already bails out
     * of resolving a {@code semantic} field's target config whenever remote indices are present, regardless
     * of round-trip mode.
     * <p>
     * One cost of this fix worth calling out: for a request mixing local and remote indices under
     * {@code ccs_minimize_roundtrips=true}, {@code ccsRemoteReduce} routes the local part through
     * {@code executeLocalSearch} directly and never reaches this hook either, so the local indices in a
     * mixed request don't get rewritten either, not just the remote ones.
     *
     * @param remoteIndices this request's remote-cluster indices, keyed by cluster alias
     */
    public static boolean hasRemoteIndices(Map<String, OriginalIndices> remoteIndices) {
        return remoteIndices.isEmpty() == false;
    }
}
