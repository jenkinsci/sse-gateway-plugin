/*
 * The MIT License
 *
 * Copyright (c) 2016, CloudBees, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package org.jenkinsci.plugins.ssegateway.sse;

import hudson.Extension;
import hudson.model.User;
import hudson.util.CopyOnWriteMap;
import jenkins.model.Jenkins;
import jenkins.util.HttpSessionListener;
import net.sf.json.JSONObject;
import org.acegisecurity.Authentication;
import org.jenkinsci.plugins.pubsub.ChannelSubscriber;
import org.jenkinsci.plugins.pubsub.EventFilter;
import org.jenkinsci.plugins.pubsub.EventProps;
import org.jenkinsci.plugins.pubsub.Message;
import org.jenkinsci.plugins.pubsub.MessageException;
import org.jenkinsci.plugins.pubsub.PubsubBus;
import org.jenkinsci.plugins.pubsub.SimpleMessage;
import org.jenkinsci.plugins.ssegateway.EventHistoryStore;
import org.jenkinsci.plugins.ssegateway.Util;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSessionEvent;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Serializable;
import java.util.Collections;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * @author <a href="mailto:tom.fennelly@gmail.com">tom.fennelly@gmail.com</a>
 */
@Restricted(NoExternalUse.class)
public abstract class EventDispatcher implements Serializable {

    public static final String SESSION_SYNC_OBJ = "org.jenkinsci.plugins.ssegateway.sse.session.sync";
    private static final Logger LOGGER = LoggerFactory.getLogger( EventDispatcher.class.getName());

    private static final ScheduledExecutorService scheduledExecutorService = Executors.newScheduledThreadPool(
        Integer.getInteger( EventDispatcher.class.getName() + ".scheduledExecutorService.size", 4 ),
        r -> new Thread( r, "EventDispatcher.retryProcessor" ));

    private volatile boolean isRetryLoopActive = false;

    // set lifetime for retry events - default 5min - 300 sec - 300000 msec
    @edu.umd.cs.findbugs.annotations.SuppressWarnings("MS_SHOULD_BE_FINAL")
    public static /* not final */ long RETRY_QUEUE_EVENT_LIFETIME = Integer.getInteger(EventDispatcher.class.getName() + ".RETRY_QUEUE_EVENT_LIFETIME", 5*60) * 1000;
    // set delay for retry loop - default 250ms
    @edu.umd.cs.findbugs.annotations.SuppressWarnings("MS_SHOULD_BE_FINAL")
    public static /* not final */ long RETRY_QUEUE_PROCESSING_DELAY = Integer.getInteger(EventDispatcher.class.getName() + ".RETRY_QUEUE_PROCESSING_DELAY", 250);

    private String id = null;
    private final transient PubsubBus bus;
    private final transient Authentication authentication;
    private transient Map<EventFilter, ChannelSubscriber> subscribers = new CopyOnWriteMap.Hash<>();

    // timestamp of last successfull dispatchEvent call
    // default to current time to avoid timeout if first call fails
    private long timestamp_dispatchEventOK = System.currentTimeMillis();

    // set timeout for unsubscribe if last successful dispatchEvent call is older than this timeout  - default: 4 hrs - 240 min - 14400 sec - 14400000 msec
    @edu.umd.cs.findbugs.annotations.SuppressWarnings("MS_SHOULD_BE_FINAL")
    public static /* not final */ long TIMEOUT_DISPATCHERFAIL = Integer.getInteger(EventDispatcher.class.getName() + ".TIMEOUT_DISPATCHERFAIL", 4*60*60) * 1000;

    // Lists of events that need to be retried on the next reconnect.
    private transient Queue<Retry> retryQueue = new ConcurrentLinkedQueue<>();
    
    public EventDispatcher() {
        this.bus = PubsubBus.getBus();
        User current = getUser();
        if (current != null) {
            this.authentication = Jenkins.getAuthentication();
        } else {
            this.authentication = Jenkins.ANONYMOUS;
        }
    }

    public abstract void start(HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException;
    public abstract HttpServletResponse getResponse();

    public Map<EventFilter, ChannelSubscriber> getSubscribers() {
        return Collections.unmodifiableMap(subscribers);
    }

    public final String getId() {
        if (id == null) {
            throw new IllegalStateException("Call to getId before the ID was set.");
        }
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    @Override
    public String toString() {
        return String.format("%s (%s)", id, System.identityHashCode(this));
    }

    /**
     * Checks if last successful dispatchEvent is older than TIMEOUT_DISPATCHERFAIL
     * if yes: suspect the dispatcher counterpart is dead
     *          - clear retry queue
     *          - remove all subscriptions
     *
     * @param step current step for log message
     */
    private void checkDispatcherFailTimeout(String step) {
        long t_curr = System.currentTimeMillis();
        long t_diff = t_curr - timestamp_dispatchEventOK;
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug(String.format("SSE dispatcher %s %s fail - %d - %d - %d", this, step, t_curr, t_diff, TIMEOUT_DISPATCHERFAIL));
        }
        if (t_diff > TIMEOUT_DISPATCHERFAIL) {
            if(LOGGER.isDebugEnabled()) {
                LOGGER.debug(
                    String.format( "SSE dispatcher %s %s fail - timediff > TIMEOUT_DISPATCHERFAIL", this, step ) );
            }
            retryQueue.clear();
            this.unsubscribeAll();
        }
    }

    /**
     * Writes a message to {@link HttpServletResponse}
     *
     * @param name event-name
     * @param data event-data
     * @throws IOException io-exception
     * @throws ServletException servlet-exception
     * @return
     *      false if the response is not writable
     */
    public synchronized boolean dispatchEvent(String name, String data) throws IOException, ServletException {
        HttpServletResponse response = getResponse();

        if (response == null) {
            checkDispatcherFailTimeout("response");
            // The SSE channel is not connected or is reconnecting after timeout.
            // Event will go to retry queue.
            return false;
        }

        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug(String.format("SSE dispatcher %s sending event: %s", this, data));
        }
        
        PrintWriter writer = response.getWriter();
        
        if (writer.checkError()) {
            checkDispatcherFailTimeout("writer.checkError");
            return false;
        }
        
        if (name != null) {
            writer.write("event: " + name + "\n");
        }
        if (data != null) {
            writer.write("data: " + data + "\n");
        }
        writer.write("\n");

        boolean writerStatus = writer.checkError();

        if (!writerStatus) {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug(String.format("SSE dispatcher %s writer ok - %d", this, System.currentTimeMillis()));
            }
            timestamp_dispatchEventOK = System.currentTimeMillis();
        } else {
            checkDispatcherFailTimeout("writer.write");
        }

        return (!writerStatus);
    }
    
    public void stop() {
        // override as needed
    }

    void setDefaultHeaders() {
        HttpServletResponse response = getResponse();
        response.setStatus(200);
        response.setContentType("text/event-stream");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control","no-cache");
        response.setHeader("Connection","keep-alive");
    }

    public boolean subscribe(@Nonnull EventFilter filter) {
        String channelName = filter.getChannelName();

        if (channelName != null) {
            SSEChannelSubscriber subscriber = (SSEChannelSubscriber) subscribers.get(filter);
            if (subscriber == null) {
                subscriber = new SSEChannelSubscriber();

                bus.subscribe(channelName, subscriber, authentication, filter);
                subscribers.put(filter, subscriber);
            } else {
                // Already subscribed to this event.
            }

            subscriber.numSubscribers++;
            publishStateEvent(SSEChannel.Event.subscribe,  new SimpleMessage()
                    .set(SSEChannel.EventProps.sse_subs_dispatcher, id)
                    .set(SSEChannel.EventProps.sse_subs_channel_name, channelName)
                    .set(SSEChannel.EventProps.sse_subs_filter, filter.toJSON())
            );

            return true;
        } else {
            LOGGER.error(String.format("Invalid SSE subscribe configuration. '%s' not specified.", EventProps.Jenkins.jenkins_channel));
        }
        
        return false;
    }

    protected User getUser() {
        return User.current();
    }

    public boolean unsubscribe(@Nonnull EventFilter filter) {
        String channelName = filter.getChannelName();
        if (channelName != null) {
            SSEChannelSubscriber subscriber = (SSEChannelSubscriber) subscribers.get(filter);
            if (subscriber != null) {
                subscriber.numSubscribers--;
                if (subscriber.numSubscribers == 0) {
                    try {
                        bus.unsubscribe(channelName, subscriber);
                    } finally {
                        subscribers.remove(filter);
                    }
                }
                publishStateEvent(SSEChannel.Event.unsubscribe,  new SimpleMessage()
                        .set(SSEChannel.EventProps.sse_subs_dispatcher, id)
                        .set(SSEChannel.EventProps.sse_subs_channel_name, channelName)
                        .set(SSEChannel.EventProps.sse_subs_filter, filter.toJSON())
                );
                return true;
            } else {
                LOGGER.info("Invalid SSE unsubscribe configuration. No active subscription for channel: {}", channelName);
            }
        } else {
            LOGGER.error(String.format("Invalid SSE unsubscribe configuration. '%s' not specified.", EventProps.Jenkins.jenkins_channel));
        }
        return false;
    }

    public void unsubscribeAll() {
        Set<Map.Entry<EventFilter, ChannelSubscriber>> entries = subscribers.entrySet();
        for (Map.Entry<EventFilter, ChannelSubscriber> entry : entries) {
            SSEChannelSubscriber subscriber = (SSEChannelSubscriber) entry.getValue();
            EventFilter filter = entry.getKey();
            String channelName = filter.getChannelName();

            bus.unsubscribe(channelName, subscriber);
        }
        subscribers.clear();
    }

    private void scheduleRetryQueueProcessing(long delay) {
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug(String.format("EventDispatcher (%s) - scheduleRetryQueueProcessing(%d)", this, delay));
        }
        if (delay > 0) {
            try {
                scheduledExecutorService.schedule(this::processRetries, delay, TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                LOGGER.info(String.format("EventDispatcher (%s) - scheduleRetryQueueProcessing - Error scheduling retry.", this), e);
            }
        } else {
            processRetries();
        }
    }

    private void publishStateEvent(SSEChannel.Event event, Message additional) {
        // Only publish these events if we're running
        // in a test.
        if (!Util.isTestEnv()) {
            return;
        }

        try {
            SimpleMessage message = new SimpleMessage()
                    .setChannelName("sse")
                    .setEventName(event)
                    .set("sse_numsubs", Integer.toString(subscribers.size()));
            if (additional != null) {
                message.putAll(additional);
            }
            bus.publish(message);
        } catch (MessageException e) {
            LOGGER.warn("Failed to publish SSE Dispatcher state event.", e);
        }
    }

    private void dispatchReload() {
        retryQueue.clear();
        try {
            dispatchEvent("reload", null);
        } catch (Exception e) {
            LOGGER.error("Unable to send reload event to client.", e);
        }
    }
    
    private void addToRetryQueue(@Nonnull Message message) {
        // check retry queue is empty
        //  -> we are adding the first element
        //  -> start the retryqueue timer
        boolean isFirstEvent = retryQueue.isEmpty();
        if (!retryQueue.add(new Retry(message))) {
            // Unable to add to the queue. Lets just tell the client
            // that it needs to reload the page.
            dispatchReload();
        } else {
            // Event was added to the queue.
            // If it was the first event -> start the retry loop timer
            if (isFirstEvent) {
                scheduleRetryQueueProcessing(RETRY_QUEUE_PROCESSING_DELAY);
            }
        }
    }

    synchronized void processRetries() {
        if (!isRetryLoopActive) {
            isRetryLoopActive = true;
            Retry retry = retryQueue.peek();

            if (retry != null) {
                long ctime = System.currentTimeMillis();
                long retry_age = (ctime - retry.timestamp);
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug(String.format("EventDispatcher (%s) - timestamp: %d - current: %d - age: %d", this, retry.timestamp, ctime, retry_age));
                }
                // check time of oldest retry envent
                if (retry_age > RETRY_QUEUE_EVENT_LIFETIME) {
                    // oldest event has timed out - remove all events from retry queue
                    LOGGER.debug("EventDispatcher {} processRetries - clear retryQueue", this);
                    retryQueue.clear();
                    retry = null;
                }
            }

            try {
                while (retry != null) {
                    try {
                        String eventJSON = EventHistoryStore.getChannelEvent(retry.channelName, retry.eventUUID);

                        if (eventJSON == null) {
                            // The event is not in the store. This can be simply because the event has
                            // not yet arrived at the store and been stored. It might need another
                            // moment or two to get there.
                            if (!retry.needsMoreTimeToLandInStore()) {
                                // Something's gone wrong. The event should be in the store by now.
                                // Lets tell the client that it needs to do a full page reload. Not much
                                // else can be done at this stage.
                                dispatchReload(); // This clears the queue too.
                                return;
                            } else {
                                // The event should be in the store (not expired, so would not
                                // have been deleted). Only explanation is that it has not yet
                                // landed there. Let's pause the retry process
                                // for a moment and retry again in the hope that the event
                                // eventually lands there. Exiting here will schedule a new run
                                // of this retry process (see finally block below).
                                return;
                            }
                        }

                        if (Util.isTestEnv()) {
                            JSONObject eventJSONObj = JSONObject.fromObject(eventJSON);
                            eventJSONObj.put(SSEChannel.EventProps.sse_dispatch_retry.name(), "true");
                            eventJSON = eventJSONObj.toString();
                        }

                        if (LOGGER.isDebugEnabled()) {
                            LOGGER.debug(String.format("EventDispatcher (%s) - retry event: %s", this, eventJSON));
                        }
                        if (!dispatchEvent(retry.channelName, eventJSON)) {
                            LOGGER.debug(String.format("EventDispatcher (%s) - Error dispatching retry event to SSE channel. dispatchEvent failed.", this));
                            return;
                        } else if (LOGGER.isDebugEnabled()) {
                            LOGGER.debug("EventDispatcher ({0}) - Dispatched retry event to SSE channel. Event {1}.", new Object[]{this, eventJSON});
                        }
                    } catch (Exception e) {
                        LOGGER.debug(String.format("EventDispatcher (%s) - Error dispatching retry event to SSE channel. Write failed.", this), e);
                        return;
                    }

                    // Only remove from the queue once successfully dispatched.
                    retryQueue.remove();
                    retry = retryQueue.peek();
                }

            } catch (Exception e) {
                LOGGER.warn(String.format("EventDispatcher (%s) - Error dispatching retry event to SSE channel. Write failed.", this), e);
                return;

            } finally {
                if (!retryQueue.isEmpty()) {
                    // For some reason the processing has exited prematurely.
                    // Schedule it to run again. We must clear this ASAP.
                    scheduleRetryQueueProcessing(RETRY_QUEUE_PROCESSING_DELAY);
                }
                isRetryLoopActive = false;
            }
            // i dont know why - but in some strange cases
            // this set of isRetryLoopActive to false is necessary
            // in my opinon the statement inside the finally should be
            // sufficient - but without this second false i had some
            // endless loops
            isRetryLoopActive = false;
        }
    }

    private void doDispatch(@Nonnull Message message) {
        if (!retryQueue.isEmpty()) {
            // We do not attempt to dispatch events directly
            // while there are events sitting in the retryQueue.
            // The retryQueue must be empty.
            addToRetryQueue(message);
        } else {
            try {
                message.set(SSEChannel.EventProps.sse_subs_dispatcher, this.id);
                message.set(SSEChannel.EventProps.sse_subs_dispatcher_inst, Integer.toString(System.identityHashCode(this)));

                if (!dispatchEvent(message.getChannelName(), message.toJSON())) {
                    LOGGER.debug("Error dispatching event to SSE channel. dispatchEvent failed.");
                    addToRetryQueue(message);
                }
            } catch (Exception e) {
                LOGGER.debug("Error dispatching event to SSE channel.", e);
                addToRetryQueue(message);
            }
        }
    }

    /**
     * Receive event from {@link PubsubBus} and sends it to this client.
     */
    private final class SSEChannelSubscriber implements ChannelSubscriber {
        private int numSubscribers = 0;

        @Override
        public void onMessage(@Nonnull Message message) {
            doDispatch(message);
        }
    }
    
    /**
     * Http session listener.
     */
    @Extension
    public static final class SSEHttpSessionListener extends HttpSessionListener {
        @Override
        public void sessionDestroyed(HttpSessionEvent httpSessionEvent) {
            try {
                Map<String, EventDispatcher> dispatchers = EventDispatcherFactory.getDispatchers(httpSessionEvent.getSession());
                try {
                    for (EventDispatcher dispatcher : dispatchers.values()) {
                        try {
                            dispatcher.unsubscribeAll();
                        } catch (Exception e) {
                            if(LOGGER.isDebugEnabled()){
                                LOGGER.debug("Error during unsubscribeAll() for dispatcher " + dispatcher.getId() + ".", e);
                            }
                        }
                    }
                } finally {
                    dispatchers.clear();
                }
            } catch (Exception e) {
                LOGGER.debug("Error during session cleanup. The session has probably timed out." + this, e);
            }
        }
    }
    
    private static class Retry {
        private final long timestamp = System.currentTimeMillis();
        private final String channelName;
        private final String eventUUID;

        private Retry(@Nonnull Message message) {
            // We want to keep the memory footprint of the retryQueue
            // to a minimum. That is why we are interning these strings
            // (multiple dispatchers will likely be retrying the same messages)
            // as well as saving the actual message bodies to file and reading those
            // back when it comes time to process the retry i.e. keep as little
            // in memory as possible + share references where we can.
            this.channelName = message.getChannelName().intern();
            this.eventUUID = message.getEventUUID().intern();
        }
        
        private boolean needsMoreTimeToLandInStore() {
            // There's no way it should take more than 10 seconds for
            // event to hit the store (10 seconds longer than it took to
            // hit the dispatcher). Once we go outside that window,
            // then we return false from this function.
            return (System.currentTimeMillis() - timestamp < 10000);
        }
    }
}
