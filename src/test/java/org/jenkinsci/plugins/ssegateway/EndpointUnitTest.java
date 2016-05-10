package org.jenkinsci.plugins.ssegateway;

import org.jenkins.pubsub.ChannelSubscriber;
import org.jenkins.pubsub.EventFilter;
import org.jenkins.pubsub.PubsubBus;
import org.jenkinsci.plugins.ssegateway.sse.EventDispatcher;
import org.jenkinsci.plugins.ssegateway.sse.EventDispatcherFactory;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.kohsuke.stapler.StaplerRequest;
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
    
    @Before
    public void setup() {
        eventDispatcher = new MockEventDispatcher();
    }
    @After
    public void tearDown() {
        eventDispatcher.unsubscribeAll();
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
        
        endpoint.doConfigure(request);

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
        endpoint.doConfigure(newRequest("/sample-config-02.json"));
        subscribers = eventDispatcher.getSubscribers();
        Assert.assertEquals(1, subscribers.size());
        
        // Unsubscribe ...
        endpoint.doConfigure(newRequest("/sample-config-03.json"));
        subscribers = eventDispatcher.getSubscribers();
        Assert.assertEquals(0, subscribers.size());
    }

    @Test
    public void test_configure_subscribe_unsubscribeAll() throws IOException, ServletException {
        Endpoint endpoint = new Endpoint() {
            @Override protected void init() {}
        };

        Map<EventFilter, ChannelSubscriber> subscribers = eventDispatcher.getSubscribers();
        Assert.assertEquals(0, subscribers.size());

        // Subscribe ...
        endpoint.doConfigure(newRequest("/sample-config-04.json"));
        subscribers = eventDispatcher.getSubscribers();
        Assert.assertEquals(2, subscribers.size());
        
        // Unsubscribe ...
        endpoint.doConfigure(newRequest("/sample-config-05.json")); // "unsubscribe": "*"
        subscribers = eventDispatcher.getSubscribers();
        Assert.assertEquals(0, subscribers.size());
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
}