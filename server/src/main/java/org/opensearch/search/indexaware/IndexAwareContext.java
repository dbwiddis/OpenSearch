/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.search.indexaware;

import org.opensearch.Version;
import org.opensearch.cluster.metadata.IndexMetadata;
import org.opensearch.core.tasks.TaskId;

import java.util.Collections;
import java.util.Map;

/**
 * Index-aware context passed to an {@code IndexAwareSearchRequestProcessor}: the concrete local indices a
 * search request targets, resolved before the coordinator query rewrite runs, with their current metadata
 * (including mappings).
 * <p>
 * v1 scope: local indices only. Remote (cross-cluster) indices are not resolved here and keep whatever
 * a processor leaves on them unchanged &mdash; a processor should check {@link #getTargetIndices()} for a
 * concrete index name before deciding to act.
 * <p>
 * Not thread-safe: one instance is built per request and handed to each registered processor in turn. With
 * the listener-based {@code IndexAwareSearchRequestProcessor#process}, that's not necessarily the same
 * thread for every processor, but it is still strictly one processor at a time, never concurrently.
 *
 * @opensearch.internal
 */
public final class IndexAwareContext {

    private final Map<String, IndexMetadata> targetIndices;
    private final IndexMappingSourceCache mappingSourceCache;
    private final Version minNodeVersion;
    private final TaskId searchTaskId;

    public IndexAwareContext(
        Map<String, IndexMetadata> targetIndices,
        IndexMappingSourceCache mappingSourceCache,
        Version minNodeVersion,
        TaskId searchTaskId
    ) {
        this.targetIndices = Collections.unmodifiableMap(targetIndices);
        this.mappingSourceCache = mappingSourceCache;
        this.minNodeVersion = minNodeVersion;
        this.searchTaskId = searchTaskId;
    }

    /**
     * The oldest node version in the cluster as of the start of this request. The source a processor
     * resolves is serialized to every data node holding a target shard, so during a rolling upgrade a
     * processor must not emit a query, aggregation or other named writeable that nodes on this version
     * can't read.
     */
    public Version getMinNodeVersion() {
        return minNodeVersion;
    }

    /**
     * The id of the search task this processor is running for, or {@link TaskId#EMPTY_TASK_ID} if there is
     * none. A processor that issues its own requests (a sub-search, a get, a model call) should set this as
     * their parent task ({@code request.setParentTask(...)}): cancelling the search then cancels them too,
     * and their resource usage is attributed to the search that caused it instead of escaping search
     * backpressure and workload management as unrelated top-level work.
     */
    public TaskId getSearchTaskId() {
        return searchTaskId;
    }

    /**
     * The concrete local indices this request targets, keyed by index name, each with its current
     * {@code IndexMetadata} (mapping, settings, etc.) as of the start of this request.
     */
    public Map<String, IndexMetadata> getTargetIndices() {
        return targetIndices;
    }

    /**
     * {@code indexName}'s mapping, parsed to an unmodifiable {@code Map}, or an empty map if
     * {@code indexName} isn't one of {@link #getTargetIndices()} or has no mapping. Delegates to the
     * node-level {@link IndexMappingSourceCache}: parsed once per (index UUID, mapping version) across the
     * node's lifetime, not once per request, so a second lookup for the same index at the same mapping
     * version &mdash; whether from a later request or another processor in this one &mdash; reuses the
     * same cached map rather than re-parsing. The returned map is unmodifiable at every level, nested
     * maps and lists included: it is shared with every other request on this node.
     */
    public Map<String, Object> getMappingSourceAsMap(String indexName) {
        final IndexMetadata metadata = targetIndices.get(indexName);
        if (metadata == null) {
            return Collections.emptyMap();
        }
        return mappingSourceCache.getMappingSourceAsMap(metadata);
    }
}
