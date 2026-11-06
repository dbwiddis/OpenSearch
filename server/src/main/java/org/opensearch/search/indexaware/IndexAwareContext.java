/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.search.indexaware;

import org.opensearch.cluster.metadata.IndexMetadata;

import java.util.Collections;
import java.util.Map;

/**
 * Index-aware context passed to an {@link IndexAwareSearchRequestProcessor}: the concrete local indices a
 * search request targets, resolved before the coordinator query rewrite runs, with their current metadata
 * (including mappings).
 * <p>
 * v1 scope: local indices only. Remote (cross-cluster) indices are not resolved here and keep whatever
 * a processor leaves on them unchanged \u2014 a processor should check {@link #getTargetIndices()} for a
 * concrete index name before deciding to act.
 *
 * @opensearch.experimental
 */
public final class IndexAwareContext {

    private final Map<String, IndexMetadata> targetIndices;

    public IndexAwareContext(Map<String, IndexMetadata> targetIndices) {
        this.targetIndices = Collections.unmodifiableMap(targetIndices);
    }

    /**
     * The concrete local indices this request targets, keyed by index name, each with its current
     * {@code IndexMetadata} (mapping, settings, etc.) as of the start of this request.
     */
    public Map<String, IndexMetadata> getTargetIndices() {
        return targetIndices;
    }
}
