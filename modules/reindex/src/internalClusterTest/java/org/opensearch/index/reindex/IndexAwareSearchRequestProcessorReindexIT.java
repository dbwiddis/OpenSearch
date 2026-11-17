/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.index.reindex;

import org.opensearch.common.settings.Settings;
import org.opensearch.index.query.MatchQueryBuilder;
import org.opensearch.index.query.QueryBuilders;
import org.opensearch.plugins.Plugin;
import org.opensearch.plugins.SearchPlugin;
import org.opensearch.search.SearchService;
import org.opensearch.search.indexaware.IndexAwareSearchRequestProcessor;
import org.opensearch.test.OpenSearchIntegTestCase;

import java.util.Collection;
import java.util.List;

import static java.util.Arrays.asList;
import static org.opensearch.test.hamcrest.OpenSearchAssertions.assertHitCount;

/**
 * Confirms {@link IndexAwareSearchRequestProcessor}'s default skip for an internal search against a real
 * {@code _reindex} job -- not a unit-level stand-in for the parent task, but an actual
 * {@link ReindexRequestBuilder} running through the real transport/task-manager machinery, with the
 * {@link BulkByScrollTask} check in
 * {@code IndexAwareSearchRequestScope#isInternalByDefault} deciding whether the processor runs.
 * <p>
 * Lives in this module, rather than alongside {@code IndexAwareSearchRequestProcessorIT} in {@code server},
 * because exercising a real {@code _reindex} call needs {@link ReindexModulePlugin} installed as a node
 * plugin, and {@code server} can't depend on this module.
 */
@OpenSearchIntegTestCase.ClusterScope(scope = OpenSearchIntegTestCase.Scope.SUITE, numDataNodes = 1)
public class IndexAwareSearchRequestProcessorReindexIT extends OpenSearchIntegTestCase {

    private static final String SOURCE_INDEX = "source_index";
    private static final String DEST_INDEX = "dest_index";

    @Override
    protected Collection<Class<? extends Plugin>> nodePlugins() {
        return asList(ReindexModulePlugin.class, AlwaysRewriteToMatchAllPlugin.class);
    }

    public void testReindexSkipsTheProcessorByDefault() {
        createIndex(SOURCE_INDEX, DEST_INDEX);
        client().prepareIndex(SOURCE_INDEX).setId("1").setSource("title", "red shoes").get();
        client().prepareIndex(SOURCE_INDEX).setId("2").setSource("title", "blue jacket").get();
        client().prepareIndex(SOURCE_INDEX).setId("3").setSource("title", "green hat").get();
        client().admin().indices().prepareRefresh(SOURCE_INDEX).get();

        // Sanity check: with the hook enabled, an ordinary top-level search against the same index and
        // query IS rewritten to match_all by this test's processor -- so the _reindex assertion below is
        // actually testing the skip, not a processor that never rewrites anything.
        setEnabled(true);
        try {
            assertHitCount(client().prepareSearch(SOURCE_INDEX).setQuery(QueryBuilders.matchQuery("title", "shoes")).get(), 3);

            // _reindex's initial search carries a BulkByScrollTask parent, so the processor must be
            // skipped: only the 1 document that literally matches "shoes" should be reindexed, not all 3
            // (which is what would happen if the match-to-match_all rewrite silently applied here too).
            new ReindexRequestBuilder(client(), ReindexAction.INSTANCE).source(SOURCE_INDEX)
                .destination(DEST_INDEX)
                .filter(QueryBuilders.matchQuery("title", "shoes"))
                .get();
        } finally {
            clearSetting();
        }

        client().admin().indices().prepareRefresh(DEST_INDEX).get();
        assertHitCount(client().prepareSearch(DEST_INDEX).setQuery(QueryBuilders.matchAllQuery()).get(), 1);
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

    /** Rewrites any plain {@code match} on {@code title} into {@code match_all}, unconditionally. */
    public static class AlwaysRewriteToMatchAllPlugin extends Plugin implements SearchPlugin {
        @Override
        public List<IndexAwareSearchRequestProcessor> getIndexAwareSearchRequestProcessors() {
            return asList((source, context, listener) -> {
                if (source.query() instanceof MatchQueryBuilder matchQuery && "title".equals(matchQuery.fieldName())) {
                    listener.onResponse(source.query(QueryBuilders.matchAllQuery()));
                    return;
                }
                listener.onResponse(source);
            });
        }
    }
}
