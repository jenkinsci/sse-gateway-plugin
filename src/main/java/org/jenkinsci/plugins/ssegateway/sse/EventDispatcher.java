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

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.Extension;
import hudson.model.User;
import hudson.util.CopyOnWriteMap;
import jenkins.model.Jenkins;
import jenkins.util.HttpSessionListener;
import org.acegisecurity.Authentication;
import org.jenkins.pubsub.ChannelSubscriber;
import org.jenkins.pubsub.EventFilter;
import org.jenkins.pubsub.EventProps;
import org.jenkins.pubsub.Message;
import org.jenkins.pubsub.MessageException;
import org.jenkins.pubsub.PubsubBus;
import org.jenkins.pubsub.SimpleMessage;
import org.jenkinsci.plugins.ssegateway.EventHistoryStore;
import org.jenkinsci.plugins.ssegateway.Util;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

import javax.annotation.Nonnull;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import javax.servlet.http.HttpSessionEvent;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Serializable;
import java.util.Collections;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * @author <a href="mailto:tom.fennelly@gmail.com">tom.fennelly@gmail.com</a>
 */
@Restricted(NoExternalUse.class)
public abstract class EventDispatcher implements Serializable {

    public static final String SESSION_SYNC_OBJ = "org.jenkinsci.plugins.ssegateway.sse.session.sync";
    private static final Logger LOGGER = Logger.getLogger(EventDispatcher.class.getName());

    private String id = null;
    private final PubsubBus bus;
    private Map<EventFilter, ChannelSubscriber> subscribers = new CopyOnWriteMap.Hash<>();
    
    // Lists of events that need to be retried on the next reconnect.
    private Queue<Retry> retryQueue = new ConcurrentLinkedQueue<>();
    
    public EventDispatcher() {
        this.bus = PubsubBus.getBus();
    }

    public abstract void start(HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException;
    public abstract HttpServletResponse getResponse();

    public Map<EventFilter, ChannelSubscriber> getSubscribers() {
        return Collections.unmodifiableMap(subscribers);
    }

    public final String getId() {
        if (id == null) {
            throw new IllegalStateException("Call to getId before the ID ewas set.");
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

    public synchronized boolean dispatchEvent(String name, String data) throws IOException, ServletException {
        HttpServletResponse response = getResponse();
        
        if (response == null) {
            // The SSE listen channel is probably not connected.
            // Events fall on the floor !!
            LOGGER.log(Level.SEVERE, String.format("Unexpected SSE dispatcher state for %s. No HTTP response instance.", this));
            return false;
        }
        
        if (LOGGER.isLoggable(Level.FINEST)) {
            LOGGER.log(Level.FINEST, String.format("SSE dispatcher %s sending event: %s", this, data));
        }
        
        PrintWriter writer = response.getWriter();
        
        if (writer.checkError()) {
            return false;
        }
        
        if (name != null) {
            writer.write("event: " + name + "\n");
        }
        if (data != null) {
            writer.write("data: " + data + "\n");
        }
        writer.write("\n");
        writer.flush();
        
        return (!writer.checkError());
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
            Authentication authentication;

            User current = getUser();
            if (current != null) {
                authentication = current.impersonate();
            } else {
                authentication = Jenkins.ANONYMOUS;
            }

            SSEChannelSubscriber subscriber = (SSEChannelSubscriber) subscribers.get(filter);
            if (subscriber == null) {
                subscriber = new SSEChannelSubscriber(this);

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
            LOGGER.log(Level.SEVERE, String.format("Invalid SSE subscribe configuration. '%s' not specified.", EventProps.Jenkins.jenkins_channel));
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
                LOGGER.log(Level.WARNING, "Invalid SSE unsubscribe configuration. No active subscription matching filter: ");
            }
        } else {
            LOGGER.log(Level.SEVERE, String.format("Invalid SSE unsubscribe configuration. '%s' not specified.", EventProps.Jenkins.jenkins_channel));
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
        if (delay > 0) {
            new Timer().schedule(new TimerTask() {
                @Override
                public void run() {
                    processRetries();
                }
            }, delay);
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
            LOGGER.log(Level.WARNING, "Failed to publish SSE Dispatcher state event.", e);
        }
    }

    private void dispatchReload() {
        retryQueue.clear();
        try {
            dispatchEvent("reload", null);
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Unable to send reload event to client.", e);
        }
    }
    
    private void addToRetryList(@Nonnull Message message) {
        if (!retryQueue.add(new Retry(message))) {
            // Unable to add to the queue. Lets just tell the client
            // that it needs to reload the page.
            dispatchReload();
        }
    }

    synchronized void processRetries() {
        Retry retry = retryQueue.peek();
        
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

                    if (!dispatchEvent(retry.channelName, eventJSON)) {
                        LOGGER.log(Level.FINE, String.format("Error dispatching retry event to SSE channel. Write failed. Dispatcher %s.", this));
                        return;
                    } else if (LOGGER.isLoggable(Level.FINEST)) {
                        LOGGER.log(Level.FINEST, "Dispatched retry event to SSE channel. Dispatcher {0}. Event {1}.", new Object[] {this, eventJSON});
                    }
                } catch (Exception e) {
                    LOGGER.log(Level.FINE, String.format("Error dispatching retry event to SSE channel. Write failed. Dispatcher %s.", this), e);
                    return;
                }

                // Only remove from the queue once successfully dispatched.
                retryQueue.remove();
                retry = retryQueue.peek();
            }

        } finally {
            if (!retryQueue.isEmpty()) {
                // For some reason the processing has exited prematurely.
                // Schedule it to run again. We must clear this ASAP.
                scheduleRetryQueueProcessing(100);
            }
        }
    }

    private void doDispatch(@Nonnull Message message) {
        if (!retryQueue.isEmpty()) {
            // We do not attempt to dispatch events directly
            // while there are events sitting in the retryQueue.
            // The retryQueue must be empty.
            addToRetryList(message);
        } else {
            try {
                message.set(SSEChannel.EventProps.sse_subs_dispatcher, this.id);
                message.set(SSEChannel.EventProps.sse_subs_dispatcher_inst, Integer.toString(System.identityHashCode(this)));

                if (!dispatchEvent(message.getChannelName(), message.toJSON())) {
                    LOGGER.log(Level.FINE, "Error dispatching event to SSE channel. Write failed.");
                    addToRetryList(message);
                }
            } catch (Exception e) {
                LOGGER.log(Level.FINE, "Error dispatching event to SSE channel.", e);
                addToRetryList(message);
            }
        }
    }
    
    private static final class SSEChannelSubscriber implements ChannelSubscriber {
        
        private EventDispatcher eventDispatcher;
        private int numSubscribers = 0;

        public SSEChannelSubscriber(EventDispatcher eventDispatcher) {
            this.eventDispatcher = eventDispatcher;
        }

        @Override
        public void onMessage(@Nonnull Message message) {
            eventDispatcher.doDispatch(message);
        }
    }
    
    /**
     * Http session listener.
     */
    @Extension
    public static final class SSEHttpSessionListener extends HttpSessionListener {
        
        public static String getSessionSyncObj(HttpSession session) {
            String syncObj = (String) session.getAttribute(SESSION_SYNC_OBJ);
            if (syncObj == null) {
                syncObj = setSessionSyncObj(session);
            }
            return syncObj;
        }
        
        @Override
        public void sessionCreated(HttpSessionEvent httpSessionEvent) {
            setSessionSyncObj(httpSessionEvent.getSession());
        }

        @Override
        public void sessionDestroyed(HttpSessionEvent httpSessionEvent) {
            Map<String, EventDispatcher> dispatchers = EventDispatcherFactory.getDispatchers(httpSessionEvent.getSession());
            try {
                for (EventDispatcher dispatcher : dispatchers.values()) {
                    dispatcher.unsubscribeAll();
                }
            } finally {
                dispatchers.clear();
            }
        }

        @SuppressFBWarnings(value = "DM_STRING_CTOR", 
                justification = "purposely doing new String() here so as to guarantee a new object instance.")
        private synchronized static String setSessionSyncObj(HttpSession session) {
            String syncObj = (String) session.getAttribute(SESSION_SYNC_OBJ);
            if (syncObj == null) {
                syncObj = new String(session.getId());
                session.setAttribute(SESSION_SYNC_OBJ, syncObj);
            }
            return syncObj;
        }
    }
    
    private static class Retry {
        private final long timestamp = System.currentTimeMillis();
        private final String channelName;
        private final String eventUUID;

        private Retry(@Nonnull Message message) {
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