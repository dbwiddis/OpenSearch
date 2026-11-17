/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.search.indexaware;

import org.opensearch.action.search.CreatePitAction;
import org.opensearch.action.search.CreatePitRequest;
import org.opensearch.action.search.CreatePitResponse;
import org.opensearch.action.search.DeletePitAction;
import org.opensearch.action.search.DeletePitRequest;
import org.opensearch.action.search.SearchRequest;
import org.opensearch.action.search.SearchResponse;
import org.opensearch.cluster.metadata.IndexNameExpressionResolver;
import org.opensearch.cluster.service.ClusterService;
import org.opensearch.common.settings.Settings;
import org.opensearch.common.unit.TimeValue;
import org.opensearch.common.util.concurrent.ThreadContext;
import org.opensearch.core.action.ActionListener;
import org.opensearch.core.common.io.stream.NamedWriteableRegistry;
import org.opensearch.core.xcontent.NamedXContentRegistry;
import org.opensearch.core.xcontent.XContentBuilder;
import org.opensearch.env.Environment;
import org.opensearch.env.NodeEnvironment;
import org.opensearch.index.query.MatchQueryBuilder;
import org.opensearch.index.query.QueryBuilders;
import org.opensearch.plugins.Plugin;
import org.opensearch.plugins.SearchPlugin;
import org.opensearch.repositories.RepositoriesService;
import org.opensearch.script.ScriptService;
import org.opensearch.search.SearchService;
import org.opensearch.search.builder.PointInTimeBuilder;
import org.opensearch.search.builder.SearchSourceBuilder;
import org.opensearch.test.OpenSearchIntegTestCase;
import org.opensearch.threadpool.ThreadPool;
import org.opensearch.transport.client.Client;
import org.opensearch.watcher.ResourceWatcherService;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static java.util.Collections.singletonList;
import static org.opensearch.common.xcontent.XContentFactory.jsonBuilder;
import static org.opensearch.test.hamcrest.OpenSearchAssertions.assertHitCount;

/**
 * End-to-end coverage for {@link IndexAwareSearchRequestProcessor}, exercising the real dispatch path in
 * {@code TransportSearchAction} (not a unit-level stand-in): a plugin-registered processor rewriting a
 * plain {@code match} based on each target index's own mapping, with no search pipeline involved. Also
 * doubles as a worked example of the reference shape the companion RFC describes: a processor reads a
 * field's existing, generic {@code meta} mapping parameter to decide whether to act &mdash; opted in per
 * index, through the field's own mapping, not a separate settings object.
 * <p>
 * The same hook's default skip behavior against a real {@code _reindex} job lives in a separate test in
 * the reindex module ({@code IndexAwareSearchRequestProcessorReindexIT}), since exercising a real reindex
 * action needs that module as a node plugin, which this module can't depend on.
 */
@OpenSearchIntegTestCase.ClusterScope(scope = OpenSearchIntegTestCase.Scope.SUITE, numDataNodes = 1)
public class IndexAwareSearchRequestProcessorIT extends OpenSearchIntegTestCase {

    private static final String OPTED_IN_INDEX = "opted_in_index";
    private static final String OPTED_OUT_INDEX = "opted_out_index";

    @Override
    protected Collection<Class<? extends Plugin>> nodePlugins() {
        return Arrays.asList(
            TestIndexAwareSearchRequestProcessorPlugin.class,
            NestedSearchTriggeringPlugin.class,
            MarkerObservingPlugin.class
        );
    }

    @Override
    public void setUp() throws Exception {
        super.setUp();
        createIndexWithTitleField(OPTED_IN_INDEX, true);
        createIndexWithTitleField(OPTED_OUT_INDEX, false);
        for (String index : new String[] { OPTED_IN_INDEX, OPTED_OUT_INDEX }) {
            client().prepareIndex(index).setId("1").setSource("title", "red shoes").get();
            client().prepareIndex(index).setId("2").setSource("title", "blue jacket").get();
            client().prepareIndex(index).setId("3").setSource("title", "green hat").get();
        }
        client().admin().indices().prepareRefresh(OPTED_IN_INDEX, OPTED_OUT_INDEX).get();
    }

    private void createIndexWithTitleField(String index, boolean optIn) throws Exception {
        XContentBuilder mapping = jsonBuilder().startObject().startObject("properties").startObject("title").field("type", "text");
        if (optIn) {
            mapping.startObject("meta").field(TestIndexAwareSearchRequestProcessorPlugin.OPT_IN_META_KEY, "true").endObject();
        }
        mapping.endObject().endObject().endObject();
        client().admin().indices().prepareCreate(index).setMapping(mapping).get();
    }

    public void testDisabledSettingLeavesEvenAnOptedInIndexUnrewritten() {
        // search.index_aware_search_request_processors.enabled is off by default: the plugin is installed
        // and the mapping opts in, but the hook must not run at all.
        SearchResponse response = client().prepareSearch(OPTED_IN_INDEX).setQuery(QueryBuilders.matchQuery("title", "shoes")).get();
        assertHitCount(response, 1);
    }

    public void testEnabledSettingRewritesAnOptedInIndex() {
        setEnabled(true);
        try {
            SearchResponse response = client().prepareSearch(OPTED_IN_INDEX).setQuery(QueryBuilders.matchQuery("title", "shoes")).get();
            // Rewritten to match_all per the opted-in index's own mapping: all 3 docs, not just the 1
            // literal "shoes" match.
            assertHitCount(response, 3);
        } finally {
            clearSetting();
        }
    }

    public void testEnabledSettingLeavesANonOptedInIndexUnrewritten() {
        setEnabled(true);
        try {
            SearchResponse response = client().prepareSearch(OPTED_OUT_INDEX).setQuery(QueryBuilders.matchQuery("title", "shoes")).get();
            // The hook ran (the setting is on), read this index's own mapping, and correctly chose not to
            // rewrite -- the per-index decision comes from the field's own mapping, not a global switch.
            assertHitCount(response, 1);
        } finally {
            clearSetting();
        }
    }

    public void testProcessorDoesNotReprocessASubSearchItIssues() {
        // The lesson the sub-search marker exists for: fused hybrid's own per-leg sub-searches used to go
        // back through this hook in an earlier internal proof of concept, got rewritten back into hybrid,
        // and recursed until a StackOverflowError. NestedSearchTriggeringPlugin reproduces the same shape
        // with a plain core processor: on seeing a trigger query, it issues one marked sub-search of the
        // identical query against the same index. If the marker works, the hook must skip that sub-search
        // instead of invoking the processor again.
        //
        // Deliberately registered second in nodePlugins() (and the test targets OPTED_OUT_INDEX, below):
        // TestIndexAwareSearchRequestProcessorPlugin's processor runs first in the dispatch loop, in
        // registration order, and this test depends on that order to let the trigger query survive
        // unrewritten to this plugin's own processor. If the companion RFC's proposed declared-ordering
        // mechanism (index_aware_processor_order) ships, dispatch order may stop being
        // registration-order-only, and this test may need to pin the order explicitly.
        NestedSearchTriggeringPlugin.INVOCATIONS.set(0);
        NestedSearchTriggeringPlugin.ISSUED_NESTED_SEARCH.set(false);
        setEnabled(true);
        try {
            // Deliberately OPTED_OUT_INDEX: TestIndexAwareSearchRequestProcessorPlugin's own processor
            // runs first in the dispatch loop and would otherwise rewrite this match into match_all before
            // NestedSearchTriggeringPlugin's processor ever saw the trigger query.
            client().prepareSearch(OPTED_OUT_INDEX).setQuery(QueryBuilders.matchQuery("title", "nested_trigger")).get();
            assertEquals(1, NestedSearchTriggeringPlugin.INVOCATIONS.get());
        } finally {
            clearSetting();
        }
    }

    public void testSubSearchMarkerDoesNotLeakIntoTheRestOfTheSearch() {
        // NestedSearchTriggeringPlugin's processor resolves from its sub-search's callback, which runs in
        // the sub-search's own thread context: marked. The processors after it, and the search itself,
        // must run in the request's context instead. Otherwise the marker scoped to one sub-search would
        // silently cover everything the outer search does from then on.
        NestedSearchTriggeringPlugin.INVOCATIONS.set(0);
        NestedSearchTriggeringPlugin.ISSUED_NESTED_SEARCH.set(false);
        NestedSearchTriggeringPlugin.RESOLVED_ON_THREAD.set(null);
        MarkerObservingPlugin.OBSERVATIONS.set(0);
        MarkerObservingPlugin.SAW_MARKER.set(false);
        setEnabled(true);
        try {
            SearchResponse response = client().prepareSearch(OPTED_OUT_INDEX)
                .setQuery(QueryBuilders.matchQuery("title", "nested_trigger"))
                .get();
            assertHitCount(response, 0);
            // The processor really did resolve asynchronously, from the sub-search's callback, rather than
            // inline on the thread that called it: otherwise this test proves nothing.
            assertNotNull(NestedSearchTriggeringPlugin.RESOLVED_ON_THREAD.get());
            assertNotEquals(Thread.currentThread().getName(), NestedSearchTriggeringPlugin.RESOLVED_ON_THREAD.get());
            // Ran for the outer search only (the sub-search is marked, so it skips the hook), unmarked.
            assertEquals(1, MarkerObservingPlugin.OBSERVATIONS.get());
            assertFalse("a later processor ran under the sub-search marker", MarkerObservingPlugin.SAW_MARKER.get());
        } finally {
            clearSetting();
        }
    }

    public void testPointInTimeSearchIsRewrittenPerItsIndexMapping() throws Exception {
        // A PIT search carries its target indices in the PIT id rather than in the request: the hook has
        // to take them from there (as the search itself does), not from an index expression.
        CreatePitRequest createPitRequest = new CreatePitRequest(TimeValue.timeValueMinutes(1), true);
        createPitRequest.setIndices(new String[] { OPTED_IN_INDEX });
        CreatePitResponse pit = client().execute(CreatePitAction.INSTANCE, createPitRequest).get();
        setEnabled(true);
        try {
            SearchResponse response = client().prepareSearch()
                .setPointInTime(new PointInTimeBuilder(pit.getId()))
                .setQuery(QueryBuilders.matchQuery("title", "shoes"))
                .get();
            assertHitCount(response, 3);
        } finally {
            clearSetting();
            client().execute(DeletePitAction.INSTANCE, new DeletePitRequest(pit.getId())).get();
        }
    }

    public void testDeletingAnIndexEvictsItsCachedMapping() throws Exception {
        String index = "evicted_index";
        createIndexWithTitleField(index, true);
        client().prepareIndex(index).setId("1").setSource("title", "red shoes").get();
        client().admin().indices().prepareRefresh(index).get();
        setEnabled(true);
        try {
            int before = cachedMappings();
            // TestIndexAwareSearchRequestProcessorPlugin reads the index's mapping through the context,
            // which parses and caches it on whichever node coordinates the search.
            assertHitCount(client().prepareSearch(index).setQuery(QueryBuilders.matchQuery("title", "shoes")).get(), 1);
            assertEquals(before + 1, cachedMappings());

            client().admin().indices().prepareDelete(index).get();
            // Evicted by a cluster state listener on the coordinating node, so not necessarily by the time
            // the delete call returns.
            assertBusy(() -> assertEquals(before, cachedMappings()));
        } finally {
            clearSetting();
        }
    }

    /** Parsed mappings currently cached across every node: any of them may have coordinated a search. */
    private static int cachedMappings() {
        int total = 0;
        for (SearchService searchService : internalCluster().getInstances(SearchService.class)) {
            total += searchService.getIndexMappingSourceCache().size();
        }
        return total;
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

    /**
     * Rewrites a plain {@code match} on {@code title} into {@code match_all} when the target index's own
     * mapping opts {@code title} in via its {@code meta} parameter -- the same {@code meta}-based shape the
     * companion RFC's reference implementation uses, pending a more general plugin-contributed mapping
     * parameter mechanism in core.
     */
    public static class TestIndexAwareSearchRequestProcessorPlugin extends Plugin implements SearchPlugin {

        static final String OPT_IN_META_KEY = "index_aware_test_rewrite";

        @Override
        public List<IndexAwareSearchRequestProcessor> getIndexAwareSearchRequestProcessors() {
            return singletonList((source, context, listener) -> {
                if (source.query() instanceof MatchQueryBuilder matchQuery && "title".equals(matchQuery.fieldName())) {
                    for (String indexName : context.getTargetIndices().keySet()) {
                        if (isOptedIn(context, indexName)) {
                            listener.onResponse(source.query(QueryBuilders.matchAllQuery()));
                            return;
                        }
                    }
                }
                listener.onResponse(source);
            });
        }

        private static boolean isOptedIn(IndexAwareContext context, String indexName) {
            Object properties = context.getMappingSourceAsMap(indexName).get("properties");
            if (properties instanceof Map<?, ?> propertiesMap && propertiesMap.get("title") instanceof Map<?, ?> titleMapping) {
                Object meta = titleMapping.get("meta");
                return meta instanceof Map<?, ?> metaMap && "true".equals(metaMap.get(OPT_IN_META_KEY));
            }
            return false;
        }
    }

    /**
     * Reproduces, with a plain core processor, the shape that caused the {@code StackOverflowError} seen
     * in an earlier internal proof of concept, which is why this hook's sub-search marker exists: on
     * seeing a trigger
     * query, the processor issues one additional, marked sub-search of the identical query shape against
     * the same index. Bounded to exactly one nested call via {@link #ISSUED_NESTED_SEARCH} regardless of
     * whether the marker actually works, so a broken marker shows up as "the processor ran twice" rather
     * than as unbounded recursion in a test run.
     */
    public static class NestedSearchTriggeringPlugin extends Plugin implements SearchPlugin {

        static final AtomicInteger INVOCATIONS = new AtomicInteger();
        static final AtomicBoolean ISSUED_NESTED_SEARCH = new AtomicBoolean();
        static final AtomicReference<String> RESOLVED_ON_THREAD = new AtomicReference<>();

        // Per plugin instance, i.e. per node, not static: the test cluster runs several nodes in one JVM, and
        // each has its own ThreadContext. A processor has to use the one belonging to the node it runs on.
        private volatile Client nodeClient;
        private volatile ThreadPool nodeThreadPool;

        @Override
        public Collection<Object> createComponents(
            Client client,
            ClusterService clusterService,
            ThreadPool threadPool,
            ResourceWatcherService resourceWatcherService,
            ScriptService scriptService,
            NamedXContentRegistry xContentRegistry,
            Environment environment,
            NodeEnvironment nodeEnvironment,
            NamedWriteableRegistry namedWriteableRegistry,
            IndexNameExpressionResolver indexNameExpressionResolver,
            Supplier<RepositoriesService> repositoriesServiceSupplier
        ) {
            nodeClient = client;
            nodeThreadPool = threadPool;
            return Collections.emptyList();
        }

        @Override
        public List<IndexAwareSearchRequestProcessor> getIndexAwareSearchRequestProcessors() {
            return singletonList((source, context, listener) -> {
                INVOCATIONS.incrementAndGet();
                if (isTrigger(source) && ISSUED_NESTED_SEARCH.compareAndSet(false, true)) {
                    String indexName = context.getTargetIndices().keySet().iterator().next();
                    SearchRequest subSearch = new SearchRequest(indexName).source(
                        new SearchSourceBuilder().query(QueryBuilders.matchQuery("title", "nested_trigger"))
                    );
                    // Cancelled with, and accounted to, the search that caused it.
                    subSearch.setParentTask(context.getSearchTaskId());
                    // The pattern the extension point documents: mark only around issuing the sub-search,
                    // never block on it, and resolve the hook's listener from the sub-search's own callback.
                    try (ThreadContext.StoredContext ignored = SubSearchMarker.markForSubSearch(nodeThreadPool.getThreadContext())) {
                        nodeClient.search(subSearch, ActionListener.wrap(response -> {
                            RESOLVED_ON_THREAD.set(Thread.currentThread().getName());
                            listener.onResponse(source);
                        }, listener::onFailure));
                    }
                } else {
                    listener.onResponse(source);
                }
            });
        }

        private static boolean isTrigger(SearchSourceBuilder source) {
            return source.query() instanceof MatchQueryBuilder matchQuery
                && "title".equals(matchQuery.fieldName())
                && "nested_trigger".equals(matchQuery.value());
        }
    }

    /**
     * Registered after {@link NestedSearchTriggeringPlugin}, so its processor runs once that one has
     * resolved from its sub-search's callback: records whether the sub-search marker is still set by then.
     */
    public static class MarkerObservingPlugin extends Plugin implements SearchPlugin {

        static final AtomicInteger OBSERVATIONS = new AtomicInteger();
        static final AtomicBoolean SAW_MARKER = new AtomicBoolean();

        private volatile ThreadPool nodeThreadPool;

        @Override
        public Collection<Object> createComponents(
            Client client,
            ClusterService clusterService,
            ThreadPool threadPool,
            ResourceWatcherService resourceWatcherService,
            ScriptService scriptService,
            NamedXContentRegistry xContentRegistry,
            Environment environment,
            NodeEnvironment nodeEnvironment,
            NamedWriteableRegistry namedWriteableRegistry,
            IndexNameExpressionResolver indexNameExpressionResolver,
            Supplier<RepositoriesService> repositoriesServiceSupplier
        ) {
            nodeThreadPool = threadPool;
            return Collections.emptyList();
        }

        @Override
        public List<IndexAwareSearchRequestProcessor> getIndexAwareSearchRequestProcessors() {
            return singletonList((source, context, listener) -> {
                OBSERVATIONS.incrementAndGet();
                if (SubSearchMarker.isMarked(nodeThreadPool.getThreadContext())) {
                    SAW_MARKER.set(true);
                }
                listener.onResponse(source);
            });
        }
    }
}
