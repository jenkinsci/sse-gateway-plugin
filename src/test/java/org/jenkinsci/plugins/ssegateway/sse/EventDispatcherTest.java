package org.jenkinsci.plugins.ssegateway.sse;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.TimeUnit;

import org.jenkinsci.plugins.pubsub.SimpleMessage;
import org.jenkinsci.plugins.ssegateway.MockEventDispatcher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class EventDispatcherTest {

    private static final long SAVE_PROCESSING_DELAY = org.jenkinsci.plugins.ssegateway.sse.EventDispatcher.RETRY_QUEUE_PROCESSING_DELAY;
    private static final long SAVE_EVENT_LIFETIME = org.jenkinsci.plugins.ssegateway.sse.EventDispatcher.RETRY_QUEUE_EVENT_LIFETIME;

    @AfterEach
    void tearDown() {
        org.jenkinsci.plugins.ssegateway.sse.EventDispatcher.RETRY_QUEUE_PROCESSING_DELAY = SAVE_PROCESSING_DELAY;
        org.jenkinsci.plugins.ssegateway.sse.EventDispatcher.RETRY_QUEUE_EVENT_LIFETIME = SAVE_EVENT_LIFETIME;
    }

    @Test
    void queueClearOnNoSubs() {
        EventDispatcher ed = new MockEventDispatcher();
        ed.addToRetryQueue(new SimpleMessage());
        assertTrue(ed.retryQueue.isEmpty());
    }

    @Test
    void queueClearTimeout() {
        EventDispatcher ed = new MockEventDispatcher();
        ed.subscribers.put(null,null);
        org.jenkinsci.plugins.ssegateway.sse.EventDispatcher.RETRY_QUEUE_EVENT_LIFETIME = -1;
        org.jenkinsci.plugins.ssegateway.sse.EventDispatcher.RETRY_QUEUE_PROCESSING_DELAY = -1;
        ed.addToRetryQueue(new SimpleMessage());
        assertTrue(ed.retryQueue.isEmpty());
    }

    @Test
    void queueClearTimeoutOnAdd() {
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
