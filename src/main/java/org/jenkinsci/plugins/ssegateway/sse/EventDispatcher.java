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
import jenkins.util.HttpSessionListener;
import org.jenkins.pubsub.ChannelSubscriber;
import org.jenkins.pubsub.EventFilter;
import org.jenkins.pubsub.EventProps;
import org.jenkins.pubsub.Message;
import org.jenkins.pubsub.PubsubBus;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

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
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * @author <a href="mailto:tom.fennelly@gmail.com">tom.fennelly@gmail.com</a>
 */
@Restricted(NoExternalUse.class)
public abstract class EventDispatcher implements Serializable {

    private static final Logger LOGGER = Logger.getLogger(EventDispatcher.class.getName());

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

    public synchronized void subscribe(@Nonnull EventFilter filter) {
        if (subscribers.containsKey(filter)) {
            // Already subscribed to this event.
            // TODO: Handle subscription narrowing/widening
            return;
        }
        
        String channelName = filter.getChannelName();
        if (channelName != null) {
            ChannelSubscriber subscriber = new ChannelSubscriber() {
                @Override
                public void onMessage(@Nonnull Message message) {
                    try {
                        dispatchEvent(message.getEventName(), message.toJSON());
                    } catch (Exception e) {
                        LOGGER.log(Level.WARNING, "Error dispatching event to SSE channel.", e);
                    }
                }
            };
            bus.subscribe(channelName, subscriber, User.current(), filter);
            subscribers.put(filter, subscriber);
        } else {
            LOGGER.log(Level.SEVERE, String.format("Invalid SSE subscribe configuration. '%s' not specified.", EventProps.Jenkins.jenkins_channel));
        }
    }

    public synchronized void unsubscribe(@Nonnull EventFilter filter) {
        String channelName = filter.getChannelName();
        if (channelName != null) {
            ChannelSubscriber subscriber = subscribers.remove(filter);
            if (subscriber != null) {
                bus.unsubscribe(channelName, subscriber);
            } else {
                LOGGER.log(Level.WARNING, String.format("Invalid SSE unsubscribe configuration. No active subscription matching filter: ", filter.toJSON()));
            }
        } else {
            LOGGER.log(Level.SEVERE, String.format("Invalid SSE unsubscribe configuration. '%s' not specified.", EventProps.Jenkins.jenkins_channel));
        }
    }

    public synchronized void unsubscribeAll() {
        Set<EventFilter> entries = subscribers.keySet();
        for (EventFilter entry : entries) {
            unsubscribe(entry);
        }
        subscribers.clear();
    }

    /**
     * Http session listener.
     */
    @Extension
    public static final class SSEHttpSessionListener extends HttpSessionListener {
        @Override
        public void sessionDestroyed(HttpSessionEvent httpSessionEvent) {
            EventDispatcherFactory.getDispatcher(httpSessionEvent.getSession()).unsubscribeAll();
        }
    }
}