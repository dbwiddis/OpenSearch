/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.extensions.action;

import org.junit.After;
import org.junit.Before;
import org.opensearch.Version;
import org.opensearch.action.ActionModule;
import org.opensearch.action.ActionModule.DynamicActionRegistry;
import org.opensearch.action.admin.cluster.state.ClusterStateAction;
import org.opensearch.action.admin.cluster.state.ClusterStateRequest;
import org.opensearch.action.support.ActionFilters;
import org.opensearch.client.node.NodeClient;
import org.opensearch.cluster.node.DiscoveryNode;
import org.opensearch.common.io.stream.NamedWriteableRegistry;
import org.opensearch.common.network.NetworkService;
import org.opensearch.common.settings.Settings;
import org.opensearch.common.transport.TransportAddress;
import org.opensearch.common.util.PageCacheRecycler;
import org.opensearch.extensions.DiscoveryExtensionNode;
import org.opensearch.extensions.AcknowledgedResponse;
import org.opensearch.extensions.rest.RestSendToExtensionActionTests;
import org.opensearch.indices.breaker.NoneCircuitBreakerService;
import org.opensearch.test.OpenSearchTestCase;
import org.opensearch.test.client.NoOpNodeClient;
import org.opensearch.test.transport.MockTransportService;
import org.opensearch.threadpool.TestThreadPool;
import org.opensearch.threadpool.ThreadPool;
import org.opensearch.transport.ActionNotFoundTransportException;
import org.opensearch.transport.NodeNotConnectedException;
import org.opensearch.transport.TransportService;
import org.opensearch.transport.nio.MockNioTransport;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import static java.util.Collections.emptyMap;
import static java.util.Collections.emptySet;

public class ExtensionTransportActionsHandlerTests extends OpenSearchTestCase {
    private static final ActionFilters EMPTY_FILTERS = new ActionFilters(Collections.emptySet());
    private TransportService transportService;
    private MockNioTransport transport;
    private DiscoveryExtensionNode discoveryExtensionNode;
    private ExtensionTransportActionsHandler extensionTransportActionsHandler;
    private NodeClient client;
    private final ThreadPool threadPool = new TestThreadPool(RestSendToExtensionActionTests.class.getSimpleName());

    @Before
    public void setup() throws Exception {
        Settings settings = Settings.builder().put("cluster.name", "test").build();
        transport = new MockNioTransport(
            settings,
            Version.CURRENT,
            threadPool,
            new NetworkService(Collections.emptyList()),
            PageCacheRecycler.NON_RECYCLING_INSTANCE,
            new NamedWriteableRegistry(Collections.emptyList()),
            new NoneCircuitBreakerService()
        );
        transportService = new MockTransportService(
            settings,
            transport,
            threadPool,
            TransportService.NOOP_TRANSPORT_INTERCEPTOR,
            (boundAddress) -> new DiscoveryNode(
                "test_node",
                "test_node",
                boundAddress.publishAddress(),
                emptyMap(),
                emptySet(),
                Version.CURRENT
            ),
            null,
            Collections.emptySet()
        );
        discoveryExtensionNode = new DiscoveryExtensionNode(
            "firstExtension",
            "uniqueid1",
            new TransportAddress(InetAddress.getByName("127.0.0.0"), 9300),
            new HashMap<String, String>(),
            Version.fromString("3.0.0"),
            Version.fromString("3.0.0"),
            Collections.emptyList()
        );
        client = spy(new NoOpNodeClient(this.getTestName()));
        ActionModule mockActionModule = mock(ActionModule.class);
        DynamicActionRegistry dynamicActionRegistry = new DynamicActionRegistry();
        dynamicActionRegistry.registerUnmodifiableActionMap(Collections.emptyMap());
        when(mockActionModule.getDynamicActionRegistry()).thenReturn(dynamicActionRegistry);
        when(mockActionModule.getActionFilters()).thenReturn(EMPTY_FILTERS);
        extensionTransportActionsHandler = new ExtensionTransportActionsHandler(
            Map.of("uniqueid1", discoveryExtensionNode),
            transportService,
            client,
            mockActionModule,
            null
        );
    }

    @Override
    @After
    public void tearDown() throws Exception {
        super.tearDown();
        transportService.close();
        client.close();
        ThreadPool.terminate(threadPool, 30, TimeUnit.SECONDS);
    }

    public void testRegisterAction() {
        String action = "test-action";
        extensionTransportActionsHandler.registerAction(action, discoveryExtensionNode.getId());
        assertEquals(discoveryExtensionNode, extensionTransportActionsHandler.getExtension(action));

        // Test duplicate action registration
        expectThrows(
            IllegalArgumentException.class,
            () -> extensionTransportActionsHandler.registerAction(action, discoveryExtensionNode.getId())
        );
        assertEquals(discoveryExtensionNode, extensionTransportActionsHandler.getExtension(action));
    }

    public void testRegisterTransportActionsRequest() {
        String action = "test-action";
        RegisterTransportActionsRequest request = new RegisterTransportActionsRequest("uniqueid1", Set.of(action));
        AcknowledgedResponse response = (AcknowledgedResponse) extensionTransportActionsHandler.handleRegisterTransportActionsRequest(
            request
        );
        assertTrue(response.getStatus());
        assertEquals(discoveryExtensionNode, extensionTransportActionsHandler.getExtension(action));

        // Test duplicate action registration
        response = (AcknowledgedResponse) extensionTransportActionsHandler.handleRegisterTransportActionsRequest(request);
        assertFalse(response.getStatus());
    }

    public void testTransportActionRequestFromExtension() throws Exception {
        String action = "test-action";
        byte[] requestBytes = "requestBytes".getBytes(StandardCharsets.UTF_8);
        TransportActionRequestFromExtension request = new TransportActionRequestFromExtension(action, requestBytes, "uniqueid1");
        RemoteExtensionActionResponse response = extensionTransportActionsHandler.handleTransportActionRequestFromExtension(request);
        assertFalse(response.isSuccess());
        String responseString = response.getResponseBytesAsString();
        assertEquals("Request failed: action [test-action] is not registered for any extension.", responseString);
    }

    public void testSendTransportRequestToExtension() throws InterruptedException {
        String action = "test-action";
        byte[] requestBytes = "request-bytes".getBytes(StandardCharsets.UTF_8);
        ExtensionActionRequest request = new ExtensionActionRequest(action, requestBytes);

        // Action not registered, expect exception
        expectThrows(
            ActionNotFoundTransportException.class,
            () -> extensionTransportActionsHandler.sendTransportRequestToExtension(request)
        );

        // Register Action
        RegisterTransportActionsRequest registerRequest = new RegisterTransportActionsRequest("uniqueid1", Set.of(action));
        AcknowledgedResponse response = (AcknowledgedResponse) extensionTransportActionsHandler.handleRegisterTransportActionsRequest(
            registerRequest
        );
        assertTrue(response.getStatus());

        expectThrows(NodeNotConnectedException.class, () -> extensionTransportActionsHandler.sendTransportRequestToExtension(request));
    }

    public void testHandleClusterStateRequest() throws Exception {
        ClusterStateRequest clusterStateRequest = new ClusterStateRequest().all();
        // The client is no-op so doesn't actually execute this request, but we can verify it's called
        extensionTransportActionsHandler.handleClusterStateRequest(clusterStateRequest);
        verify(client, times(1)).execute(eq(ClusterStateAction.INSTANCE), eq(clusterStateRequest), any());
    }
}
