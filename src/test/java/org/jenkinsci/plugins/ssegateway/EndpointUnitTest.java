package org.jenkinsci.plugins.ssegateway;

import org.jenkinsci.plugins.pubsub.ChannelSubscriber;
import org.jenkinsci.plugins.pubsub.EventFilter;
import org.jenkinsci.plugins.pubsub.PubsubBus;
import org.jenkinsci.plugins.ssegateway.sse.EventDispatcher;
import org.jenkinsci.plugins.ssegateway.sse.EventDispatcherFactory;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;
import org.mockito.Mockito;

import javax.servlet.ServletException;
import javax.servlet.http.HttpSession;
import java.io.IOException;
import java.util.Map;

/**
 * @author <a href="mailto:tom.fennelly@gmail.com">tom.fennelly@gmail.com</a>
 */
public class EndpointUnitTest {
    
    private EventDispatcher eventDispatcher;
    private StaplerResponse response;
    
    @Before
    public void setup() {
        SubscriptionConfigQueue.start();
        eventDispatcher = new MockEventDispatcher();
        response = Mockito.mock(StaplerResponse.class);
    }

    @After
    public void tearDown() throws Exception  {
        eventDispatcher.unsubscribeAll();
        SubscriptionConfigQueue.stop();
        waitForQueueStopped();
    }

    @AfterClass
    public static void shutDownBus() {
        PubsubBus.getBus().shutdown();
    }

    @Test
    public void test_configure_empty_config() throws IOException, ServletException {
        Endpoint endpoint = new Endpoint() {
            @Override protected void init() {}
        };
        StaplerRequest request = newRequest("/sample-config-01.json");
        
        endpoint.doConfigure(request, response);

        Map<EventFilter, ChannelSubscriber> subscribers = eventDispatcher.getSubscribers();
        Assert.assertEquals(0, subscribers.size());
    }

    @Test
    public void test_configure_subscribe_unsubscribe() throws IOException, ServletException {
        Endpoint endpoint = new Endpoint() {
            @Override protected void init() {}
        };

        Map<EventFilter, ChannelSubscriber> subscribers = eventDispatcher.getSubscribers();
        Assert.assertEquals(0, subscribers.size());

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
    public void test_configure_subscribe_unsubscribeAll() throws IOException, ServletException {
        Endpoint endpoint = new Endpoint() {
            @Override protected void init() {}
        };

        Map<EventFilter, ChannelSubscriber> subscribers = eventDispatcher.getSubscribers();
        Assert.assertEquals(0, subscribers.size());

        // Subscribe ...
        endpoint.doConfigure(newRequest("/sample-config-04.json"), response);
        subscribers = eventDispatcher.getSubscribers();
        waitForCountToGrow(subscribers, 2);

        // Unsubscribe ...
        endpoint.doConfigure(newRequest("/sample-config-05.json"), response); // "unsubscribe": "*"
        subscribers = eventDispatcher.getSubscribers();
        waitForCountToShrink(subscribers, 0);
    }

    private StaplerRequest newRequest(String config) throws IOException {
        StaplerRequest request = Mockito.mock(StaplerRequest.class);
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
                Assert.fail("Timed out waiting for subscribers count/size to reach " + count);
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Assert.fail("Timed out waiting for subscribers count/size to reach " + count);
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
                Assert.fail("Timed out waiting for subscribers count/size to reach " + count);
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Assert.fail("Timed out waiting for subscribers count/size to reach " + count);
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
                Assert.fail("Timed out waiting for queue to stop");
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Assert.fail("Timed out waiting for queue to stop");
            }
        }
    }
}
