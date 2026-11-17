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
import org.opensearch.test.OpenSearchTestCase;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;

public class IndexMappingSourceCacheTests extends OpenSearchTestCase {

    private static final String UUID = "the-same-index-uuid";

    public void testReusesTheSameParsedMapAcrossSeparateLookups() {
        IndexMappingSourceCache cache = new IndexMappingSourceCache();
        IndexMetadata metadata = indexAt(UUID, 1, Map.of("properties", Map.of("title", Map.of("type", "text"))));

        // Two separate calls -- standing in for two separate requests, or two processors in one request --
        // for the same (index UUID, mapping version) must not re-parse: same cached instance back.
        Map<String, Object> first = cache.getMappingSourceAsMap(metadata);
        Map<String, Object> second = cache.getMappingSourceAsMap(metadata);
        assertSame(first, second);
    }

    public void testDifferentMetadataInstanceSameVersionStillHits() {
        IndexMappingSourceCache cache = new IndexMappingSourceCache();
        Map<String, Object> mapping = Map.of("properties", Map.of("title", Map.of("type", "text")));
        // Two distinct IndexMetadata objects (as resolveIndices() would build fresh per request), but the
        // same index UUID and mapping version -- i.e. the mapping hasn't actually changed between requests.
        IndexMetadata first = indexAt(UUID, 1, mapping);
        IndexMetadata second = indexAt(UUID, 1, mapping);

        Map<String, Object> firstResult = cache.getMappingSourceAsMap(first);
        Map<String, Object> secondResult = cache.getMappingSourceAsMap(second);
        assertSame(firstResult, secondResult);
    }

    public void testMappingVersionChangeReplacesTheIndexEntry() {
        IndexMappingSourceCache cache = new IndexMappingSourceCache();
        IndexMetadata v1 = indexAt(UUID, 1, Map.of("properties", Map.of("title", Map.of("type", "text"))));
        IndexMetadata v2 = indexAt(UUID, 2, Map.of("properties", Map.of("title", Map.of("type", "keyword"))));

        Map<String, Object> resultV1 = cache.getMappingSourceAsMap(v1);
        Map<String, Object> resultV2 = cache.getMappingSourceAsMap(v2);

        assertNotSame(resultV1, resultV2);
        assertEquals("text", ((Map<?, ?>) ((Map<?, ?>) resultV1.get("properties")).get("title")).get("type"));
        assertEquals("keyword", ((Map<?, ?>) ((Map<?, ?>) resultV2.get("properties")).get("title")).get("type"));
        // One entry per index, not one per version: the old version's map isn't retained.
        assertEquals(1, cache.size());
        assertSame(resultV2, cache.getMappingSourceAsMap(v2));
    }

    public void testDifferentIndexUuidSameVersionIsACacheMiss() {
        IndexMappingSourceCache cache = new IndexMappingSourceCache();
        Map<String, Object> mapping = Map.of("properties", Map.of("title", Map.of("type", "text")));
        IndexMetadata indexA = indexAt("uuid-a", 1, mapping);
        IndexMetadata indexB = indexAt("uuid-b", 1, mapping);

        Map<String, Object> resultA = cache.getMappingSourceAsMap(indexA);
        Map<String, Object> resultB = cache.getMappingSourceAsMap(indexB);
        assertNotSame(resultA, resultB);
        assertEquals(2, cache.size());
    }

    public void testNoMappingIsEmpty() {
        IndexMappingSourceCache cache = new IndexMappingSourceCache();
        IndexMetadata noMapping = IndexMetadata.builder("no-mapping-index")
            .settings(settings(Version.CURRENT).put(IndexMetadata.SETTING_INDEX_UUID, UUID))
            .numberOfShards(1)
            .numberOfReplicas(0)
            .build();

        assertEquals(Collections.emptyMap(), cache.getMappingSourceAsMap(noMapping));
        assertEquals(0, cache.size());
    }

    public void testOlderVersionDoesNotEvictANewerOne() {
        IndexMappingSourceCache cache = new IndexMappingSourceCache();
        IndexMetadata v1 = indexAt(UUID, 1, Map.of("properties", Map.of("title", Map.of("type", "text"))));
        IndexMetadata v2 = indexAt(UUID, 2, Map.of("properties", Map.of("title", Map.of("type", "keyword"))));

        Map<String, Object> resultV2 = cache.getMappingSourceAsMap(v2);
        // A request still holding the older cluster state gets its own version's mapping...
        Map<String, Object> resultV1 = cache.getMappingSourceAsMap(v1);
        assertEquals("text", ((Map<?, ?>) ((Map<?, ?>) resultV1.get("properties")).get("title")).get("type"));
        // ...without displacing the newer entry everyone else is using.
        assertSame(resultV2, cache.getMappingSourceAsMap(v2));
        assertEquals(1, cache.size());
    }

    public void testConcurrentLookupsShareOneParsedMap() throws Exception {
        IndexMappingSourceCache cache = new IndexMappingSourceCache();
        IndexMetadata metadata = indexAt(UUID, 1, Map.of("properties", Map.of("title", Map.of("type", "text"))));
        int threads = 8;
        CyclicBarrier start = new CyclicBarrier(threads);
        List<Map<String, Object>> results = new CopyOnWriteArrayList<>();
        List<Thread> workers = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            Thread worker = new Thread(() -> {
                try {
                    start.await();
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
                for (int j = 0; j < 100; j++) {
                    results.add(cache.getMappingSourceAsMap(metadata));
                }
            });
            workers.add(worker);
            worker.start();
        }
        for (Thread worker : workers) {
            worker.join();
        }
        assertEquals(threads * 100, results.size());
        Map<String, Object> first = results.get(0);
        for (Map<String, Object> result : results) {
            assertSame(first, result);
        }
        assertEquals(1, cache.size());
    }

    public void testRemoveDropsADeletedIndex() {
        IndexMappingSourceCache cache = new IndexMappingSourceCache();
        IndexMetadata metadata = indexAt(UUID, 1, Map.of("properties", Map.of()));
        cache.getMappingSourceAsMap(metadata);
        assertEquals(1, cache.size());

        cache.remove(metadata.getIndex());
        assertEquals(0, cache.size());
    }

    public void testCachedMapIsUnmodifiableAtEveryLevel() {
        IndexMappingSourceCache cache = new IndexMappingSourceCache();
        IndexMetadata metadata = indexAt(
            UUID,
            1,
            Map.of("properties", Map.of("title", Map.of("type", "text", "copy_to", List.of("a", "b"))))
        );

        Map<String, Object> source = cache.getMappingSourceAsMap(metadata);
        expectThrows(UnsupportedOperationException.class, () -> source.put("x", "y"));
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) source.get("properties");
        expectThrows(UnsupportedOperationException.class, () -> properties.put("x", "y"));
        @SuppressWarnings("unchecked")
        Map<String, Object> title = (Map<String, Object>) properties.get("title");
        expectThrows(UnsupportedOperationException.class, () -> title.put("type", "keyword"));
        @SuppressWarnings("unchecked")
        List<Object> copyTo = (List<Object>) title.get("copy_to");
        expectThrows(UnsupportedOperationException.class, () -> copyTo.add("c"));
    }

    private static IndexMetadata indexAt(String indexUuid, long mappingVersion, Map<String, Object> mapping) {
        return IndexMetadata.builder("index-" + indexUuid)
            .settings(settings(Version.CURRENT).put(IndexMetadata.SETTING_INDEX_UUID, indexUuid))
            .mappingVersion(mappingVersion)
            .putMapping(new MappingMetadata("_doc", mapping))
            .numberOfShards(1)
            .numberOfReplicas(0)
            .build();
    }
}
