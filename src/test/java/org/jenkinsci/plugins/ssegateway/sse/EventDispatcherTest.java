package org.jenkinsci.plugins.ssegateway.sse;

import static org.junit.Assert.*;

import java.util.concurrent.TimeUnit;

import org.jenkinsci.plugins.pubsub.SimpleMessage;
import org.jenkinsci.plugins.ssegateway.MockEventDispatcher;
import org.junit.Before;
import org.junit.Test;

public class EventDispatcherTest {
    private static final long saveProcessingDelay = org.jenkinsci.plugins.ssegateway.sse.EventDispatcher.RETRY_QUEUE_PROCESSING_DELAY;
    private static final long saveEventLifetime = org.jenkinsci.plugins.ssegateway.sse.EventDispatcher.RETRY_QUEUE_EVENT_LIFETIME;

    @Before
    public void reset() {
        org.jenkinsci.plugins.ssegateway.sse.EventDispatcher.RETRY_QUEUE_PROCESSING_DELAY = saveProcessingDelay;
        org.jenkinsci.plugins.ssegateway.sse.EventDispatcher.RETRY_QUEUE_EVENT_LIFETIME = saveEventLifetime;
    }

    @Test
    public void queueClearOnNoSubs() throws Exception {
        EventDispatcher ed = new MockEventDispatcher();
        ed.addToRetryQueue(new SimpleMessage());
        assertTrue(ed.retryQueue.isEmpty());
    }

    @Test
    public void queueClearTimeout() throws Exception {
        EventDispatcher ed = new MockEventDispatcher();
        ed.subscribers.put(null,null);
        org.jenkinsci.plugins.ssegateway.sse.EventDispatcher.RETRY_QUEUE_EVENT_LIFETIME = -1;
        org.jenkinsci.plugins.ssegateway.sse.EventDispatcher.RETRY_QUEUE_PROCESSING_DELAY = -1;
        ed.addToRetryQueue(new SimpleMessage());
        assertTrue(ed.retryQueue.isEmpty());
    }

    @Test
    public void queueClearTimeoutOnAdd() throws Exception {
        EventDispatcher ed = new MockEventDispatcher();
        ed.subscribers.put(null,null);
        org.jenkinsci.plugins.ssegateway.sse.EventDispatcher.RETRY_QUEUE_EVENT_LIFETIME = -1;
        //Set to an hour to simulate dispatching retries failing
        org.jenkinsci.plugins.ssegateway.sse.EventDispatcher.RETRY_QUEUE_PROCESSING_DELAY = TimeUnit.HOURS.toMillis(1);
        ed.addToRetryQueue(new SimpleMessage());
        assertEquals(1, ed.retryQueue.size());
        ed.addToRetryQueue(new SimpleMessage());
        //Queue item expired and new item was added so size is still 1
        assertEquals(1, ed.retryQueue.size());
    }
}
