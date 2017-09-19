package org.jenkinsci.plugins.ssegateway;

import com.google.common.collect.ImmutableMap;
import org.jenkinsci.plugins.ssegateway.sse.EventDispatcher;
import org.junit.Before;
import org.junit.Test;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import static org.jenkinsci.plugins.ssegateway.sse.EventDispatcherFactory.DISPATCHER_SESSION_KEY;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Matchers.anyMap;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class SseServletBaseTest {
    private SseServletBase testServletBase;

    @Before
    public void setUp() throws Exception {
        testServletBase = new SseServletBase();
    }

    @Test
    public void initDispatcher_nullClientId() throws Exception {
        final HttpServletRequest mockRequest = mock(HttpServletRequest.class);
        final HttpServletResponse mockResponse = mock(HttpServletResponse.class);

        when(mockRequest.getParameter("clientId")).thenReturn(null);

        try {
            // method under test
            testServletBase.initDispatcher(mockRequest, mockResponse);
            fail("should have caught IOException");
        } catch (final IOException e) {
            assertTrue(e.getMessage().equals("No 'clientId' parameter specified in connect request."));
        }
    }

    @Test
    public void initDispatcher_existingDispatcher() throws Exception {
        final HttpServletRequest mockRequest = mock(HttpServletRequest.class);
        final HttpServletResponse mockResponse = mock(HttpServletResponse.class);
        final HttpSession mockSession = mock(HttpSession.class);
        final EventDispatcher mockDispatcher = mock(EventDispatcher.class);

        when(mockRequest.getParameter("clientId")).thenReturn("clientId");
        when(mockRequest.getSession()).thenReturn(mockSession);
        Map<String, EventDispatcher> dispatchers = ImmutableMap.of("clientId", mockDispatcher);
        when(mockSession.getAttribute(DISPATCHER_SESSION_KEY)).thenReturn(dispatchers);

        // method under test
        testServletBase.initDispatcher(mockRequest, mockResponse);

        verify(mockDispatcher).unsubscribeAll();
        verify(mockResponse).setStatus(HttpServletResponse.SC_OK);
    }

    @Test
    public void initDispatcher_newDispatcher() throws Exception {
        final HttpServletRequest mockRequest = mock(HttpServletRequest.class);
        final HttpServletResponse mockResponse = mock(HttpServletResponse.class);
        final HttpSession mockSession = mock(HttpSession.class);
        final EventDispatcher mockDispatcher = mock(EventDispatcher.class);

        when(mockRequest.getParameter("clientId")).thenReturn("clientId");
        when(mockRequest.getSession()).thenReturn(mockSession);
        when(mockSession.getAttribute(DISPATCHER_SESSION_KEY)).thenReturn(null).thenReturn(new HashMap<>());

        // method under test
        testServletBase.initDispatcher(mockRequest, mockResponse);

        verify(mockSession).setAttribute(anyString(), anyMap());
        verify(mockDispatcher, never()).unsubscribeAll();
        verify(mockResponse).setStatus(HttpServletResponse.SC_OK);
    }

    @Test
    public void validateSubscriptionConfig() throws Exception {
        final SubscriptionConfigQueue.SubscriptionConfig mockConfig = mock(SubscriptionConfigQueue.SubscriptionConfig.class);

        when(mockConfig.getDispatcherId()).thenReturn("dispatcherId");
        when(mockConfig.hasConfigs()).thenReturn(true);

        // method under test
        assertTrue(testServletBase.validateSubscriptionConfig(mockConfig));
    }

    @Test
    public void enqueueSubscriptionConfig() throws Exception {
        final SubscriptionConfigQueue.SubscriptionConfig mockConfig = mock(SubscriptionConfigQueue.SubscriptionConfig.class);

        try {
            SubscriptionConfigQueue.start();

            // method under test
            assertTrue(testServletBase.enqueueSubscriptionConfig(mockConfig));
        } finally {
            SubscriptionConfigQueue.stop();
        }
    }

    @Test
    public void ping() throws Exception {
        final HttpServletRequest mockRequest = mock(HttpServletRequest.class);
        final HttpSession mockSession = mock(HttpSession.class);
        final EventDispatcher mockDispatcher = mock(EventDispatcher.class);

        when(mockRequest.getParameter("dispatcherId")).thenReturn("dispatcherId");
        when(mockRequest.getSession()).thenReturn(mockSession);
        Map<String, EventDispatcher> dispatchers = ImmutableMap.of("dispatcherId", mockDispatcher);
        when(mockSession.getAttribute(DISPATCHER_SESSION_KEY)).thenReturn(dispatchers);

        // method under test
        assertTrue(testServletBase.ping(mockRequest));

        verify(mockDispatcher).dispatchEvent("pingback", "ack");
    }

    @Test
    public void ping_servletException() throws Exception {
        final HttpServletRequest mockRequest = mock(HttpServletRequest.class);
        final HttpSession mockSession = mock(HttpSession.class);
        final EventDispatcher mockDispatcher = mock(EventDispatcher.class);

        when(mockRequest.getParameter("dispatcherId")).thenReturn("dispatcherId");
        when(mockRequest.getSession()).thenReturn(mockSession);
        Map<String, EventDispatcher> dispatchers = ImmutableMap.of("dispatcherId", mockDispatcher);
        when(mockSession.getAttribute(DISPATCHER_SESSION_KEY)).thenReturn(dispatchers);
        when(mockDispatcher.dispatchEvent("pingback", "ack")).thenThrow(new ServletException());

        // method under test
        assertFalse(testServletBase.ping(mockRequest));
    }
}