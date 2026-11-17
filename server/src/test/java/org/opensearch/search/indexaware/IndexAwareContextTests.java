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
import org.opensearch.cluster.metadata.MappingMetadata;
import org.opensearch.core.tasks.TaskId;
import org.opensearch.test.OpenSearchTestCase;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class IndexAwareContextTests extends OpenSearchTestCase {

    public void testGetTargetIndices() {
        IndexMetadata metadata = indexWithMapping("my-index", Map.of("properties", Map.of("title", Map.of("type", "text"))));
        IndexAwareContext context = new IndexAwareContext(
            Map.of("my-index", metadata),
            new IndexMappingSourceCache(),
            Version.CURRENT,
            TaskId.EMPTY_TASK_ID
        );

        assertEquals(Map.of("my-index", metadata), context.getTargetIndices());
    }

    public void testGetTargetIndicesIsUnmodifiable() {
        IndexMetadata metadata = indexWithMapping("my-index", Map.of("properties", Map.of()));
        IndexAwareContext context = new IndexAwareContext(
            new HashMap<>(Map.of("my-index", metadata)),
            new IndexMappingSourceCache(),
            Version.CURRENT,
            TaskId.EMPTY_TASK_ID
        );

        assertThrows(UnsupportedOperationException.class, () -> context.getTargetIndices().put("other-index", metadata));
    }

    public void testGetMappingSourceAsMap() {
        Map<String, Object> mapping = Map.of("properties", Map.of("title", Map.of("type", "text")));
        IndexMetadata metadata = indexWithMapping("my-index", mapping);
        IndexAwareContext context = new IndexAwareContext(
            Map.of("my-index", metadata),
            new IndexMappingSourceCache(),
            Version.CURRENT,
            TaskId.EMPTY_TASK_ID
        );

        assertEquals(mapping, context.getMappingSourceAsMap("my-index"));
    }

    public void testGetMappingSourceAsMapIsSharedAcrossContexts() {
        // The point of delegating to IndexMappingSourceCache rather than keeping a context-local map:
        // two different IndexAwareContext instances (standing in for two different requests) backed by
        // the same node-level cache must not re-parse an unchanged mapping on the second one.
        IndexMetadata metadata = indexWithMapping("my-index", Map.of("properties", Map.of()));
        IndexMappingSourceCache sharedCache = new IndexMappingSourceCache();
        IndexAwareContext firstRequestContext = new IndexAwareContext(
            Map.of("my-index", metadata),
            sharedCache,
            Version.CURRENT,
            TaskId.EMPTY_TASK_ID
        );
        IndexAwareContext secondRequestContext = new IndexAwareContext(
            Map.of("my-index", metadata),
            sharedCache,
            Version.CURRENT,
            TaskId.EMPTY_TASK_ID
        );

        Map<String, Object> first = firstRequestContext.getMappingSourceAsMap("my-index");
        Map<String, Object> second = secondRequestContext.getMappingSourceAsMap("my-index");
        assertSame(first, second);
    }

    public void testGetMappingSourceAsMapUnknownIndexIsEmpty() {
        IndexAwareContext context = new IndexAwareContext(
            Collections.emptyMap(),
            new IndexMappingSourceCache(),
            Version.CURRENT,
            TaskId.EMPTY_TASK_ID
        );

        assertEquals(Collections.emptyMap(), context.getMappingSourceAsMap("not-a-target-index"));
    }

    public void testGetMappingSourceAsMapNoMappingIsEmpty() {
        IndexMetadata metadata = IndexMetadata.builder("my-index")
            .settings(settings(Version.CURRENT))
            .numberOfShards(1)
            .numberOfReplicas(0)
            .build();
        IndexAwareContext context = new IndexAwareContext(
            Map.of("my-index", metadata),
            new IndexMappingSourceCache(),
            Version.CURRENT,
            TaskId.EMPTY_TASK_ID
        );

        assertNull(metadata.mapping());
        assertEquals(Collections.emptyMap(), context.getMappingSourceAsMap("my-index"));
    }

    private static IndexMetadata indexWithMapping(String name, Map<String, Object> mapping) {
        return IndexMetadata.builder(name)
            .settings(settings(Version.CURRENT))
            .putMapping(new MappingMetadata("_doc", mapping))
            .numberOfShards(1)
            .numberOfReplicas(0)
            .build();
    }
}
