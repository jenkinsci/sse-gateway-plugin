package org.jenkinsci.plugins.ssegateway;

import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.TimeUnit;

import org.jenkinsci.plugins.pubsub.Message;
import org.jenkinsci.plugins.pubsub.SimpleMessage;
import org.jenkinsci.plugins.ssegateway.sse.EventDispatcher;
import org.junit.BeforeClass;
import org.junit.Test;

public class EventDispatcherTest {

    private static Method addToRetryQueue;
    private static Field retryQueue;
    private static Field subscribers;

    @BeforeClass
    public static void setup() throws Exception {
        addToRetryQueue = EventDispatcher.class.getDeclaredMethod("addToRetryQueue", Message.class);
        addToRetryQueue.setAccessible(true);
        retryQueue = EventDispatcher.class.getDeclaredField("retryQueue");
        retryQueue.setAccessible(true);
        subscribers = EventDispatcher.class.getDeclaredField("subscribers");
        subscribers.setAccessible(true);
    }

    @Test
    public void queueClearNoSubs() throws Exception {
        EventDispatcher ed = new MockEventDispatcher();
        addToRetryQueue.invoke(ed, new SimpleMessage());
        assertTrue(((Queue<?>)retryQueue.get(ed)).isEmpty());
    }

    @Test
    public void queueClearTimeout() throws Exception {
        EventDispatcher ed = new MockEventDispatcher();
        ((Map<?,?>)subscribers.get(ed)).put(null,null);
        final long saveProcessingDelay = org.jenkinsci.plugins.ssegateway.sse.EventDispatcher.RETRY_QUEUE_PROCESSING_DELAY;
        final long saveEventLifetime = org.jenkinsci.plugins.ssegateway.sse.EventDispatcher.RETRY_QUEUE_EVENT_LIFETIME;
        org.jenkinsci.plugins.ssegateway.sse.EventDispatcher.RETRY_QUEUE_EVENT_LIFETIME = -1;
        org.jenkinsci.plugins.ssegateway.sse.EventDispatcher.RETRY_QUEUE_PROCESSING_DELAY = -1;
        addToRetryQueue.invoke(ed, new SimpleMessage());
        assertTrue(((Queue<?>)retryQueue.get(ed)).isEmpty());
        org.jenkinsci.plugins.ssegateway.sse.EventDispatcher.RETRY_QUEUE_PROCESSING_DELAY = saveProcessingDelay;
        org.jenkinsci.plugins.ssegateway.sse.EventDispatcher.RETRY_QUEUE_EVENT_LIFETIME = saveEventLifetime;
    }

    @Test
    public void queueClearTimeoutOnAdd() throws Exception {
        EventDispatcher ed = new MockEventDispatcher();
        ((Map<?,?>)subscribers.get(ed)).put(null,null);
        final long saveProcessingDelay = org.jenkinsci.plugins.ssegateway.sse.EventDispatcher.RETRY_QUEUE_PROCESSING_DELAY;
        final long saveEventLifetime = org.jenkinsci.plugins.ssegateway.sse.EventDispatcher.RETRY_QUEUE_EVENT_LIFETIME;
        org.jenkinsci.plugins.ssegateway.sse.EventDispatcher.RETRY_QUEUE_EVENT_LIFETIME = -1;
        org.jenkinsci.plugins.ssegateway.sse.EventDispatcher.RETRY_QUEUE_PROCESSING_DELAY = TimeUnit.HOURS.toMillis(1);
        addToRetryQueue.invoke(ed, new SimpleMessage());
        assertEquals(1, ((Queue<?>)retryQueue.get(ed)).size());
        addToRetryQueue.invoke(ed, new SimpleMessage());
        //Queue item expired and new item was added so size is still 1
        assertEquals(1, ((Queue<?>)retryQueue.get(ed)).size());
        org.jenkinsci.plugins.ssegateway.sse.EventDispatcher.RETRY_QUEUE_PROCESSING_DELAY = saveProcessingDelay;
        org.jenkinsci.plugins.ssegateway.sse.EventDispatcher.RETRY_QUEUE_EVENT_LIFETIME = saveEventLifetime;
    }
}
