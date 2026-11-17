/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.search.indexaware;

import org.opensearch.common.settings.Settings;
import org.opensearch.common.unit.TimeValue;
import org.opensearch.index.query.QueryBuilders;
import org.opensearch.plugins.Plugin;
import org.opensearch.search.SearchService;
import org.opensearch.test.OpenSearchIntegTestCase;

import java.util.Collection;
import java.util.Collections;

/**
 * Guards against the hook holding a search thread while a processor waits on its own sub-search. The search
 * pool also runs the shard query phase, so with a one-thread pool, anything that occupied that thread for
 * the duration of a processor's sub-search (an earlier version forked
 * {@link IndexAwareSearchRequestProcessor#process} onto {@code ThreadPool.Names.SEARCH} and waited there)
 * would leave the sub-search no thread to run on, and the search would never complete.
 * <p>
 * Uses {@link IndexAwareSearchRequestProcessorIT.NestedSearchTriggeringPlugin}, whose processor follows the
 * documented non-blocking pattern: it issues its sub-search and resolves the hook's listener from that
 * sub-search's callback. So with a one-thread pool the rest of the outer search (the remaining processors,
 * the coordinator rewrite, the fan-out to shards) continues on the very thread the sub-search completed on,
 * and has to do so without waiting on it.
 * <p>
 * Scoped to its own cluster rather than shrinking the shared {@code IndexAwareSearchRequestProcessorIT}
 * cluster's pool, and bounded by {@code actionGet(TimeValue)} so a regression fails with a timeout instead
 * of hanging the build.
 */
@OpenSearchIntegTestCase.ClusterScope(scope = OpenSearchIntegTestCase.Scope.SUITE, numDataNodes = 1)
public class IndexAwareSearchRequestProcessorDeadlockIT extends OpenSearchIntegTestCase {

    private static final String INDEX = "deadlock_test_index";

    @Override
    protected Collection<Class<? extends Plugin>> nodePlugins() {
        return Collections.singletonList(IndexAwareSearchRequestProcessorIT.NestedSearchTriggeringPlugin.class);
    }

    @Override
    public Settings nodeSettings(int nodeOrdinal) {
        // The one condition the deadlock needs: a search pool with no spare thread.
        return Settings.builder().put(super.nodeSettings(nodeOrdinal)).put("thread_pool.search.size", 1).build();
    }

    @Override
    public void setUp() throws Exception {
        super.setUp();
        createIndex(INDEX);
        client().prepareIndex(INDEX).setId("1").setSource("title", "red shoes").get();
        client().admin().indices().prepareRefresh(INDEX).get();
    }

    public void testProcessorIssuingASubSearchDoesNotDeadlockAOneThreadSearchPool() {
        IndexAwareSearchRequestProcessorIT.NestedSearchTriggeringPlugin.INVOCATIONS.set(0);
        IndexAwareSearchRequestProcessorIT.NestedSearchTriggeringPlugin.ISSUED_NESTED_SEARCH.set(false);
        setEnabled(true);
        try {
            // Hangs here if anything in the hook holds the pool's one thread while the processor's
            // sub-search is outstanding.
            client().prepareSearch(INDEX)
                .setQuery(QueryBuilders.matchQuery("title", "nested_trigger"))
                .execute()
                .actionGet(TimeValue.timeValueSeconds(30));
            // The sub-search itself is marked, so it must not have been counted as a second top-level
            // invocation of the processor -- confirming the request actually completed, not that it merely
            // avoided the specific hang this test targets.
            assertEquals(1, IndexAwareSearchRequestProcessorIT.NestedSearchTriggeringPlugin.INVOCATIONS.get());
        } finally {
            clearSetting();
        }
    }

    private void setEnabled(boolean enabled) {
        client().admin()
            .cluster()
            .prepareUpdateSettings()
            .setTransientSettings(
                Settings.builder().put(SearchService.INDEX_AWARE_SEARCH_REQUEST_PROCESSORS_ENABLED_SETTING.getKey(), enabled)
            )
            .get();
    }

    private void clearSetting() {
        client().admin()
            .cluster()
            .prepareUpdateSettings()
            .setTransientSettings(Settings.builder().putNull(SearchService.INDEX_AWARE_SEARCH_REQUEST_PROCESSORS_ENABLED_SETTING.getKey()))
            .get();
    }
}
