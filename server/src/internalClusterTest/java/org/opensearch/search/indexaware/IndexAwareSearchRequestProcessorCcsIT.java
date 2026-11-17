/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.search.indexaware;

import org.opensearch.action.search.SearchRequest;
import org.opensearch.action.search.SearchResponse;
import org.opensearch.common.settings.Settings;
import org.opensearch.common.xcontent.XContentFactory;
import org.opensearch.core.xcontent.XContentBuilder;
import org.opensearch.index.query.QueryBuilders;
import org.opensearch.plugins.Plugin;
import org.opensearch.search.SearchService;
import org.opensearch.search.builder.SearchSourceBuilder;
import org.opensearch.test.AbstractMultiClustersTestCase;
import org.opensearch.transport.client.Client;

import java.util.Collection;
import java.util.Collections;

import static org.opensearch.test.hamcrest.OpenSearchAssertions.assertHitCount;

/**
 * Regression coverage for a review finding on this hook's original cross-cluster-search handling: with
 * {@code ccs_minimize_roundtrips=true} (the default), the assumption was that each remote cluster runs its
 * own complete, independent search, including its own hook invocation against its own mappings &mdash;
 * so no special handling was needed. That assumption was wrong for a request that mixes local and remote
 * indices: {@code TransportSearchAction#ccsRemoteReduce} builds the remote cluster's request from
 * {@code SearchRequest.subSearchRequest}, whose private constructor copies this local coordinator's
 * {@code searchRequest.source()} as-is &mdash; already rewritten by this hook, if it ran, before the
 * local/remote branching decision even happens. The remote cluster's own hook then runs a second time on
 * that already-rewritten source, not on the client's original query.
 * <p>
 * {@link IndexAwareSearchRequestScope#hasRemoteIndices} now bails out of the whole hook whenever remote
 * indices are present, under any {@code ccs_minimize_roundtrips} mode, mirroring {@code NeuralQueryBuilder}'s
 * own existing policy. One cost of that fix, also covered here: a request mixing local and remote indices
 * doesn't get the local indices rewritten either, not just the remote ones, since the whole hook is skipped
 * for the request, not just the remote part of it.
 * <p>
 * A second, related fix covered here: that same {@code hasRemoteIndices} check only ever runs on the
 * coordinator that received the client's original request. Once {@code ccsRemoteReduce} forwards a
 * {@code SearchRequest.subSearchRequest} to a remote cluster, that remote cluster's own
 * {@code TransportSearchAction} runs {@code doExecute} on it as if it were a fresh top-level request --
 * {@code hasRemoteIndices} sees no remote indices from that cluster's own point of view, so without a second
 * check it would run this hook again, rewriting the remote leg on its own mapping while the coordinator's
 * {@code hasRemoteIndices} skip left the local leg (and, in the mixed case, the whole request) unrewritten:
 * asymmetric, and arguably more surprising than either "always rewrite" or "never rewrite" would be.
 * {@code TransportSearchAction} now also skips whenever {@code SearchRequest.getLocalClusterAlias()} is
 * non-null -- true only for a request a remote coordinator forwarded to this node, never for a client's own
 * original request -- making every CCS leg this hook ever sees skip uniformly, with or without
 * {@code hasRemoteIndices} catching it first.
 * <p>
 * Setup happens per test method, not in a {@code setUp()} override: {@link AbstractMultiClustersTestCase}
 * starts its clusters via its own {@code @Before} method, and JUnit doesn't order {@code @Before} methods
 * across a class hierarchy, so a {@code setUp()} override here could run before the clusters exist.
 *
 * @see #testPureRemoteSearchIsAlsoSkippedByTheSymmetryFixEvenWhenOptedIn
 */
public class IndexAwareSearchRequestProcessorCcsIT extends AbstractMultiClustersTestCase {

    private static final String REMOTE_CLUSTER = "cluster_a";
    private static final String LOCAL_INDEX = "local_index";
    private static final String REMOTE_INDEX = "prod";

    @Override
    protected Collection<String> remoteClusterAlias() {
        return Collections.singleton(REMOTE_CLUSTER);
    }

    @Override
    protected boolean reuseClusters() {
        return false;
    }

    @Override
    protected Collection<Class<? extends Plugin>> nodePlugins(String clusterAlias) {
        // The same opt-in-via-mapping-meta processor as IndexAwareSearchRequestProcessorIT, installed on
        // both clusters: the point of this test is cross-cluster behavior, not a different processor.
        return Collections.singletonList(IndexAwareSearchRequestProcessorIT.TestIndexAwareSearchRequestProcessorPlugin.class);
    }

    public void testMixedLocalAndRemoteIndicesAreNotRewrittenUnderMinimizeRoundtrips() throws Exception {
        setUpClustersAndIndices();
        // Before the fix: both sides came back as if rewritten to match_all (3 local + 3 remote = 6 hits),
        // because the local coordinator's hook ran before the local/remote split, and ccsRemoteReduce
        // forwarded that already-rewritten source straight to the remote cluster. After the fix: the whole
        // hook is skipped for this request (it has a remote index), so neither side is rewritten -- 1 local
        // literal "shoes" hit + 1 remote literal "shoes" hit.
        SearchResponse response = client(LOCAL_CLUSTER).prepareSearch(LOCAL_INDEX, REMOTE_CLUSTER + ":" + REMOTE_INDEX)
            .setQuery(QueryBuilders.matchQuery("title", "shoes"))
            .get();
        assertHitCount(response, 2);
    }

    public void testMixedLocalAndRemoteIndicesAreNotRewrittenWithoutMinimizeRoundtrips() throws Exception {
        setUpClustersAndIndices();
        // The second path to the same bug (shouldMinimizeRoundtrips forces the non-minimized path for
        // PIT/scroll/DFS/collapse-with-inner-hits even when isCcsMinimizeRoundtrips() is true, sending the
        // rewritten source straight to remote shards) isn't separately exercised here, but goes through
        // the same hasRemoteIndices check this covers directly.
        SearchRequest request = new SearchRequest(LOCAL_INDEX, REMOTE_CLUSTER + ":" + REMOTE_INDEX).source(
            new SearchSourceBuilder().query(QueryBuilders.matchQuery("title", "shoes"))
        );
        request.setCcsMinimizeRoundtrips(false);
        SearchResponse response = client(LOCAL_CLUSTER).search(request).actionGet();
        assertHitCount(response, 2);
    }

    public void testPureRemoteSearchWithoutOptInStaysUnrewritten() throws Exception {
        setUpClustersAndIndices(false);
        // No local index in the request at all: ccsRemoteReduce's single-remote-cluster branch forwards
        // this node's original query straight to the remote cluster via a subSearchRequest. That
        // subSearchRequest carries a non-null getLocalClusterAlias(), so the remote cluster's own hook is
        // skipped too (see the class doc's second fix) -- but REMOTE_INDEX isn't opted in here either, so
        // this alone can't tell "skipped" apart from "ran and correctly chose not to rewrite". It's the
        // literal-1-hit baseline for the opted-in variant right below, which can tell them apart.
        SearchResponse response = client(LOCAL_CLUSTER).prepareSearch(REMOTE_CLUSTER + ":" + REMOTE_INDEX)
            .setQuery(QueryBuilders.matchQuery("title", "shoes"))
            .get();
        assertHitCount(response, 1);
    }

    public void testPureRemoteSearchIsAlsoSkippedByTheSymmetryFixEvenWhenOptedIn() throws Exception {
        setUpClustersAndIndices(true);
        // Same request shape as the test above, but REMOTE_INDEX is opted in this time. Before the
        // getLocalClusterAlias() fix, the remote cluster's own hook ran normally here (nothing in its own
        // view looks like a CCS request), saw the opt-in, and rewrote to match_all: 3 hits. After the fix,
        // the forwarded subSearchRequest's getLocalClusterAlias() skips the hook on the remote leg too, so
        // the opt-in is never even consulted: back to the literal 1 hit, matching the un-opted-in case.
        SearchResponse response = client(LOCAL_CLUSTER).prepareSearch(REMOTE_CLUSTER + ":" + REMOTE_INDEX)
            .setQuery(QueryBuilders.matchQuery("title", "shoes"))
            .get();
        assertHitCount(response, 1);
    }

    private void setUpClustersAndIndices() throws Exception {
        setUpClustersAndIndices(false);
    }

    private void setUpClustersAndIndices(boolean remoteOptIn) throws Exception {
        createIndexWithTitleField(client(LOCAL_CLUSTER), LOCAL_INDEX, true);
        createIndexWithTitleField(client(REMOTE_CLUSTER), REMOTE_INDEX, remoteOptIn);
        setEnabled(client(LOCAL_CLUSTER), true);
        setEnabled(client(REMOTE_CLUSTER), true);
    }

    private static void createIndexWithTitleField(Client client, String index, boolean optIn) throws Exception {
        XContentBuilder mapping = XContentFactory.jsonBuilder()
            .startObject()
            .startObject("properties")
            .startObject("title")
            .field("type", "text");
        if (optIn) {
            mapping.startObject("meta")
                .field(IndexAwareSearchRequestProcessorIT.TestIndexAwareSearchRequestProcessorPlugin.OPT_IN_META_KEY, "true")
                .endObject();
        }
        mapping.endObject().endObject().endObject();
        client.admin().indices().prepareCreate(index).setMapping(mapping).get();
        client.prepareIndex(index).setId("1").setSource("title", "red shoes").get();
        client.prepareIndex(index).setId("2").setSource("title", "blue jacket").get();
        client.prepareIndex(index).setId("3").setSource("title", "green hat").get();
        client.admin().indices().prepareRefresh(index).get();
    }

    private static void setEnabled(Client client, boolean enabled) {
        client.admin()
            .cluster()
            .prepareUpdateSettings()
            .setTransientSettings(
                Settings.builder().put(SearchService.INDEX_AWARE_SEARCH_REQUEST_PROCESSORS_ENABLED_SETTING.getKey(), enabled)
            )
            .get();
    }
}
