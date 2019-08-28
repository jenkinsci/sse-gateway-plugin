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
package org.jenkinsci.plugins.ssegateway;

import net.sf.json.JSONArray;
import net.sf.json.JSONException;
import net.sf.json.JSONObject;
import org.jenkinsci.plugins.pubsub.EventFilter;
import org.jenkinsci.plugins.ssegateway.sse.EventDispatcher;
import org.jenkinsci.plugins.ssegateway.sse.EventDispatcherFactory;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.StaplerRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpSession;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * @author <a href="mailto:tom.fennelly@gmail.com">tom.fennelly@gmail.com</a>
 */
@Restricted(NoExternalUse.class)
final class SubscriptionConfigQueue {

    private static final Logger LOGGER = LoggerFactory.getLogger( SubscriptionConfigQueue.class.getName());
    
    private static BlockingQueue<SubscriptionConfig> queue = null;
    
    private SubscriptionConfigQueue() {
    }

    static boolean isStarted() {
        return (queue != null);
    }
    
    static synchronized void start() {
        if (queue != null) {
            LOGGER.info("SSE Configure Queue already started. Ignoring unexpected request to start again.");
            return;
        }
        
        // We create a blocking queue that stores subscription requests coming from
        // clients and then fire off 1 thread to process the queue.
        // TF: Might look at using an Executor for this, but I think this is fine for now.
        queue = new LinkedBlockingQueue<>();
        new Thread(() -> {

                try {
                    while (true) {
                        SubscriptionConfig subscriptionConfig = queue.take();
                        if (subscriptionConfig == SubscriptionConfig.STOP_CONFIG) {
                            // Event object used to tell
                            // the process to bail out.
                            return;
                        } else {
                            try {
                                doConfigure(subscriptionConfig);
                            } catch (Exception e) {
                                LOGGER.error("Error processing SSE configuration request.", e);
                            }
                        }
                    }
                } catch (InterruptedException e) {
                    LOGGER.info("SSE configure queue processing interrupted. Stopping.", e);
                } finally {
                    queue = null;
                }
            }, "SubscriptionConfigQueue.start"
        ).start();
    }

    static synchronized void stop() {
        if (queue == null) {
            LOGGER.info("SSE Configure Queue is not started. Ignoring unexpected request to stop.");
            return;
        }

        try {
            queue.put(SubscriptionConfig.STOP_CONFIG);
        } catch (InterruptedException e) {
            LOGGER.error("Unexpected error stopping SSE Configure Queue.", e);
        }
    }

    /**
     * Queues up the application of {@link SubscriptionConfig}.
     */
    static boolean add(SubscriptionConfig subscriptionConfig) {
        return queue.offer(subscriptionConfig);
    }
    
    private static void doConfigure(SubscriptionConfig subscriptionConfig) {
        EventDispatcher dispatcher = EventDispatcherFactory.getDispatcher(subscriptionConfig.dispatcherId, subscriptionConfig.session);

        if (dispatcher == null) {
            LOGGER.warn("Failed Jenkins SSE Gateway configuration request. Unknown SSE event dispatcher " + subscriptionConfig.dispatcherId);
            return;
        }

        if (subscriptionConfig.unsubscribeAll) {
            dispatcher.unsubscribeAll();
        }
        for (EventFilter filter : subscriptionConfig.unsubscribeSet) {
            if (dispatcher.unsubscribe(filter)) {
                EventHistoryStore.onChannelUnsubscribe(filter.getChannelName());
            }
        }
        for (EventFilter filter : subscriptionConfig.subscribeSet) {
            if (dispatcher.subscribe(filter)) {
                EventHistoryStore.onChannelSubscribe(filter.getChannelName());
            }
        }
        
        if (subscriptionConfig.batchId != null) {
            try {
                JSONObject data = new JSONObject();
                data.put("batchId", subscriptionConfig.batchId);
                data.put("dispatcherId", dispatcher.getId());
                data.put("dispatcherInst", System.identityHashCode(dispatcher));
                dispatcher.dispatchEvent("configure", data.toString());
            } catch (Exception e) {
                LOGGER.error("Error sending configuration ACK for batchId=" + subscriptionConfig.batchId, e);
            }
        }
    }

    static class SubscriptionConfig {
        
        private static final SubscriptionConfig STOP_CONFIG = new SubscriptionConfig();

        private String batchId;
        private String dispatcherId;
        private HttpSession session;
        private List<EventFilter> subscribeSet = Collections.emptyList();
        private List<EventFilter> unsubscribeSet = Collections.emptyList();
        private boolean unsubscribeAll = false;

        public String getBatchId() {
            return batchId;
        }

        String getDispatcherId() {
            return dispatcherId;
        }

        static SubscriptionConfig fromRequest(StaplerRequest request) throws IOException {
            JSONObject payload = Util.readJSONPayload(request);
            SubscriptionConfig config = new SubscriptionConfig();
            
            config.batchId = request.getParameter("batchId");
            config.session = request.getSession();
            config.dispatcherId = payload.optString("dispatcherId", null);
            if (config.dispatcherId != null) {
                config.subscribeSet = extractFilterSet(payload, "subscribe");
                config.unsubscribeSet = extractFilterSet(payload, "unsubscribe");
                if (config.unsubscribeSet.isEmpty()) {
                    String unsubscribe = payload.optString("unsubscribe", null);
                    if ("*".equals(unsubscribe) || "all".equalsIgnoreCase(unsubscribe)) {
                        config.unsubscribeAll = true;
                    }
                }
            }
            
            return config;
        }

        private static List<EventFilter> extractFilterSet(JSONObject payload, String key) {
            JSONArray jsonObjs = payload.optJSONArray(key);
            
            if (jsonObjs != null && !jsonObjs.isEmpty()) {
                List<EventFilter> filterSet = new ArrayList<>();
                for (int i = 0; i < jsonObjs.size(); i++) {
                    try {
                        JSONObject jsonObj = jsonObjs.getJSONObject(i);
                        EventFilter filter = (EventFilter) jsonObj.toBean(EventFilter.class);
                        filterSet.add(filter);
                    } catch (JSONException e) {
                        LOGGER.error("Invalid SSE payload. Expecting an array of JSON Objects for property " + key, e);
                    }
                }
                return filterSet;
            }

            return Collections.emptyList();
        }

        public boolean hasConfigs() {
            return !(subscribeSet.isEmpty() && unsubscribeSet.isEmpty()) || unsubscribeAll;
        }
    }
}
