package org.jenkinsci.plugins.ssegateway;

import static org.junit.jupiter.api.Assertions.*;

import org.jenkinsci.plugins.pubsub.ChannelSubscriber;
import org.jenkinsci.plugins.pubsub.EventFilter;
import org.jenkinsci.plugins.pubsub.PubsubBus;
import org.jenkinsci.plugins.ssegateway.sse.EventDispatcher;
import org.jenkinsci.plugins.ssegateway.sse.EventDispatcherFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.StaplerResponse2;
import org.mockito.Mockito;

import jakarta.servlet.http.HttpSession;
import java.util.Map;

/**
 * @author <a href="mailto:tom.fennelly@gmail.com">tom.fennelly@gmail.com</a>
 */
class EndpointUnitTest {

    private EventDispatcher eventDispatcher;
    private StaplerResponse2 response;

    @BeforeEach
    void setUp() {
        SubscriptionConfigQueue.start();
        eventDispatcher = new MockEventDispatcher();
        response = Mockito.mock(StaplerResponse2.class);
    }

    @AfterEach
    void tearDown() {
        eventDispatcher.unsubscribeAll();
        SubscriptionConfigQueue.stop();
        waitForQueueStopped();
    }

    @AfterAll
    static void tearDownAll() {
        PubsubBus.getBus().shutdown();
    }

    @Test
    void test_configure_empty_config() throws Exception {
        Endpoint endpoint = new Endpoint() {
            @Override protected void init() {}
        };
        StaplerRequest2 request = newRequest("/sample-config-01.json");

        endpoint.doConfigure(request, response);

        Map<EventFilter, ChannelSubscriber> subscribers = eventDispatcher.getSubscribers();
        assertEquals(0, subscribers.size());
    }

    @Test
    void test_configure_subscribe_unsubscribe() throws Exception {
        Endpoint endpoint = new Endpoint() {
            @Override protected void init() {}
        };

        Map<EventFilter, ChannelSubscriber> subscribers = eventDispatcher.getSubscribers();
        assertEquals(0, subscribers.size());

        // Subscribe ...
        endpoint.doConfigure(newRequest("/sample-config-02.json"), response);
        subscribers = eventDispatcher.getSubscribers();
        waitForCountToGrow(subscribers, 1);

        // Unsubscribe ...
        endpoint.doConfigure(newRequest("/sample-config-03.json"), response);
        subscribers = eventDispatcher.getSubscribers();
        waitForCountToShrink(subscribers, 0);
    }

    @Test
    void test_configure_subscribe_unsubscribeAll() throws Exception {
        Endpoint endpoint = new Endpoint() {
            @Override protected void init() {}
        };

        Map<EventFilter, ChannelSubscriber> subscribers = eventDispatcher.getSubscribers();
        assertEquals(0, subscribers.size());

        // Subscribe ...
        endpoint.doConfigure(newRequest("/sample-config-04.json"), response);
        subscribers = eventDispatcher.getSubscribers();
        waitForCountToGrow(subscribers, 2);

        // Unsubscribe ...
        endpoint.doConfigure(newRequest("/sample-config-05.json"), response); // "unsubscribe": "*"
        subscribers = eventDispatcher.getSubscribers();
        waitForCountToShrink(subscribers, 0);
    }

    private StaplerRequest2 newRequest(String config) throws Exception {
        StaplerRequest2 request = Mockito.mock(StaplerRequest2.class);
        HttpSession session = Mockito.mock(HttpSession.class);
        Map dispatchers = Mockito.mock(Map.class);

        Mockito.when(dispatchers.get("1111111111")).thenReturn(eventDispatcher);
        Mockito.when(request.getSession()).thenReturn(session);
        Mockito.when(session.getAttribute(EventDispatcher.SESSION_SYNC_OBJ)).thenReturn("blah");
        Mockito.when(session.getAttribute(EventDispatcherFactory.DISPATCHER_SESSION_KEY)).thenReturn(dispatchers);
        Mockito.when(request.getInputStream()).thenReturn(new MockServletInputStream(config));

        return request;
    }

    private void waitForCountToGrow(Map<EventFilter, ChannelSubscriber> subscribers, int count) {
        long start = System.currentTimeMillis();
        while (true) {
            if (subscribers.size() >= count) {
                return;
            }
            if (System.currentTimeMillis() > (start + 10000)) {
                fail("Timed out waiting for subscribers count/size to reach " + count);
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                fail("Timed out waiting for subscribers count/size to reach " + count);
            }
        }
    }

    private void waitForCountToShrink(Map<EventFilter, ChannelSubscriber> subscribers, int count) {
        long start = System.currentTimeMillis();
        while (true) {
            if (subscribers.size() <= count) {
                return;
            }
            if (System.currentTimeMillis() > (start + 10000)) {
                fail("Timed out waiting for subscribers count/size to reach " + count);
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                fail("Timed out waiting for subscribers count/size to reach " + count);
            }
        }
    }

    private void waitForQueueStopped() {
        long start = System.currentTimeMillis();
        while (true) {
            if (!SubscriptionConfigQueue.isStarted()) {
                return;
            }
            if (System.currentTimeMillis() > (start + 10000)) {
                fail("Timed out waiting for queue to stop");
                return;
            }
            assertDoesNotThrow(() -> Thread.sleep(50), "Timed out waiting for queue to stop");
        }
    }
}
