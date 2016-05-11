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
import hudson.Functions;
import hudson.model.User;
import hudson.util.CopyOnWriteMap;
import jenkins.util.HttpSessionListener;
import org.jenkins.pubsub.ChannelSubscriber;
import org.jenkins.pubsub.EventFilter;
import org.jenkins.pubsub.EventProps;
import org.jenkins.pubsub.Message;
import org.jenkins.pubsub.MessageException;
import org.jenkins.pubsub.PubsubBus;
import org.jenkins.pubsub.SimpleMessage;
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
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * @author <a href="mailto:tom.fennelly@gmail.com">tom.fennelly@gmail.com</a>
 */
@Restricted(NoExternalUse.class)
public abstract class EventDispatcher implements Serializable {

    public static final String SESSION_SYNC_OBJ = "org.jenkinsci.plugins.ssegateway.sse.session.sync";
    private static final Logger LOGGER = Logger.getLogger(EventDispatcher.class.getName());

    private final String id = UUID.randomUUID().toString();
    private final PubsubBus bus;
    private Map<EventFilter, ChannelSubscriber> subscribers = new CopyOnWriteMap.Hash<>();

    public EventDispatcher() {
        this.bus = PubsubBus.getBus();
    }

    public abstract void start(HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException;
    public abstract HttpServletResponse getResponse();

    public Map<EventFilter, ChannelSubscriber> getSubscribers() {
        return Collections.unmodifiableMap(subscribers);
    }

    public final String getId() {
        return id;
    }

    public boolean dispatchEvent(String name, String data) throws IOException, ServletException {
        HttpServletResponse response = getResponse();
        
        if (response == null) {
            // The SSE listen channel is probably not connected.
            // Events fall on the floor !!
            return false;
        }
        
        PrintWriter writer = response.getWriter();
        if (name != null) {
            writer.write("event: " + name + "\n");
        }
        if (data != null) {
            writer.write("data: " + data + "\n");
        }
        writer.write("\n");
        writer.flush();
        
        return true;
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
        SSEChannelSubscriber subscriber = (SSEChannelSubscriber) subscribers.get(filter);
        if (subscriber != null) {
            // Already subscribed to this event.
            subscriber.numSubscribers++;
            publishStateEvent("subscribe");
            return true;
        }
        
        String channelName = filter.getChannelName();
        if (channelName != null) {
            User current = getUser();
            
            if (current == null) {
                current = User.get("anonymous");
            }

            subscriber = new SSEChannelSubscriber(this);

            bus.subscribe(channelName, subscriber, current, filter);
            subscribers.put(filter, subscriber);
            subscriber.numSubscribers++;
            publishStateEvent("subscribe");

            return true;
        } else {
            LOGGER.log(Level.SEVERE, String.format("Invalid SSE subscribe configuration. '%s' not specified.", EventProps.Jenkins.jenkins_channel));
        }
        
        return false;
    }

    protected User getUser() {
        return User.current();
    }

    public void unsubscribe(@Nonnull EventFilter filter) {
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
                publishStateEvent("unsubscribe");
            } else {
                LOGGER.log(Level.WARNING, "Invalid SSE unsubscribe configuration. No active subscription matching filter: ");
            }
        } else {
            LOGGER.log(Level.SEVERE, String.format("Invalid SSE unsubscribe configuration. '%s' not specified.", EventProps.Jenkins.jenkins_channel));
        }
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

    private void publishStateEvent(String event) {
        // Only publish these events if we're running
        // in a test.
        if (!Functions.getIsUnitTest()) {
            return;
        }

        try {
            bus.publish(
                new SimpleMessage()
                    .setChannelName("sse")
                    .setEventName(event)
                    .set("sse_numsubs", Integer.toString(subscribers.size()))
            );
        } catch (MessageException e) {
            LOGGER.log(Level.WARNING, "Failed to publish SSE Dispatcher state event.", e);
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
            try {
                eventDispatcher.dispatchEvent(message.getChannelName(), message.toJSON());
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Error dispatching event to SSE channel.", e);
            }
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
                if (Functions.getIsUnitTest()) {
                    // Fudge things for unit/integration tests as HttpSessionListeners do
                    // not get called on session create.
                    syncObj = setSessionSyncObj(session);
                } else {
                    // This should never happen as the sessionCreated function adds this at the
                    // very start of the session.
                    throw new IllegalStateException("Unexpected/illegal state. The session should have a " + SESSION_SYNC_OBJ);
                }
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
        private static String setSessionSyncObj(HttpSession session) {
            String syncObj = new String(session.getId());
            session.setAttribute(SESSION_SYNC_OBJ, syncObj);
            return syncObj;
        }
    }
}