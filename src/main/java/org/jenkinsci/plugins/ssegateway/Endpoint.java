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

import hudson.Extension;
import hudson.model.RootAction;
import hudson.security.csrf.CrumbExclusion;
import hudson.util.HttpResponses;
import hudson.util.PluginServletFilter;
import jenkins.model.Jenkins;
import net.sf.json.JSONArray;
import net.sf.json.JSONException;
import net.sf.json.JSONObject;
import org.jenkins.pubsub.EventFilter;
import org.jenkinsci.plugins.ssegateway.sse.EventDispatcher;
import org.jenkinsci.plugins.ssegateway.sse.EventDispatcherFactory;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.DoNotUse;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.HttpResponse;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;
import org.kohsuke.stapler.interceptor.RequirePOST;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.io.File;
import java.io.IOException;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * @author <a href="mailto:tom.fennelly@gmail.com">tom.fennelly@gmail.com</a>
 */
@Restricted(NoExternalUse.class)
@Extension
public class Endpoint extends CrumbExclusion implements RootAction {

    public static final String SSE_GATEWAY_URL = "/sse-gateway";
    public static final String SSE_LISTEN_URL_PREFIX = SSE_GATEWAY_URL + "/listen/";
    
    private static final Logger LOGGER = Logger.getLogger(Endpoint.class.getName());

    public Endpoint() throws ServletException {
        init();
    }

    @Override
    public boolean process(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws IOException, ServletException {
        String contentType = request.getHeader("Content-Type");
        
        if(contentType != null && contentType.contains("application/json")) {
            String requestedResource = getRequestedResourcePath(request);

            if (requestedResource.equals(SSE_GATEWAY_URL + "/configure")) {
                chain.doFilter(request, response);
                return true;
            }
        }

        return false;
    }

    protected void init() throws ServletException {
        Jenkins jenkins = Jenkins.getInstance();
        if (jenkins != null) {
            try {
                EventHistoryStore.setHistoryRoot(new File(jenkins.getRootDir(), "sse-event-store"));
                EventHistoryStore.enableAutoDeleteOnExpire();
            } catch (IOException e) {
                LOGGER.log(Level.SEVERE, "Unexpected error setting EventHistoryStore event history root dir.", e);
            }
        }
        PluginServletFilter.addFilter(new SSEListenChannelFilter());
    }

    @Override
    public String getIconFileName() {
        return null;
    }

    @Override
    public String getDisplayName() {
        return null;
    }

    @Override
    public String getUrlName() {
        return SSE_GATEWAY_URL;
    }
    
    @Restricted(DoNotUse.class) // Web only
    public HttpResponse doConnect(StaplerRequest request, StaplerResponse response) throws IOException {
        String clientId = request.getParameter("clientId");
        
        if (clientId == null) {
            throw new IOException("No 'clientId' parameter specified in connect request.");
        }
        
        HttpSession session = request.getSession();
        EventDispatcher dispatcher = EventDispatcherFactory.getDispatcher(clientId, session);
        
        // If there was already a dispatcher with this ID, then remove
        // all subscriptions from it and reuse the instance.
        if (dispatcher != null) {
            LOGGER.log(Level.FINE, "We already have a Dispatcher for clientId {0}. Removing all subscriptions on the existing Dispatcher instance and reusing it.", dispatcher.toString());
            dispatcher.unsubscribeAll();
        } else {
            // Else create a new instance with this id.
            EventDispatcherFactory.newDispatcher(clientId, session);
        }
        
        response.setStatus(HttpServletResponse.SC_OK);
        return HttpResponses.okJSON(); 
    }
    
    @RequirePOST
    @Restricted(DoNotUse.class) // Web only
    public HttpResponse doConfigure(StaplerRequest request, StaplerResponse response) throws IOException {
        HttpSession session = request.getSession();
        int failedSubscribes = 0;
        String batchId = request.getParameter("batchId");
        
        LOGGER.log(Level.FINE, "Processing configuration request. batchId={0}", batchId);
        
        // We want to ensure that, at one time, only one set of configurations are being applied,
        // for a given user session. 
        synchronized (EventDispatcher.SSEHttpSessionListener.getSessionSyncObj(session)) {
            SubscriptionConfig subscriptionConfig = SubscriptionConfig.fromRequest(request);

            if (subscriptionConfig.dispatcherId == null) {
                response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                return HttpResponses.errorJSON("'dispatcherId' not specified.");
            } else if (!subscriptionConfig.hasConfigs()) {
                response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                return HttpResponses.errorJSON("No 'subscribe' or 'unsubscribe' configurations provided in configuration request.");
            } else {
                EventDispatcher dispatcher = EventDispatcherFactory.getDispatcher(subscriptionConfig.dispatcherId, request.getSession());

                if (dispatcher == null) {
                    return HttpResponses.errorJSON("Failed Jenkins SSE Gateway configuration request. Unknown SSE event dispatcher " + subscriptionConfig.dispatcherId);
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
                    } else {
                        failedSubscribes++;
                    }
                }
                
                if (batchId != null) {
                    try {
                        JSONObject data = new JSONObject();
                        data.put("batchId", batchId);
                        data.put("dispatcherId", dispatcher.getId());
                        data.put("dispatcherInst", System.identityHashCode(dispatcher));
                        dispatcher.dispatchEvent("configure", data.toString());
                    } catch (ServletException e) {
                        LOGGER.log(Level.SEVERE, "Error sending configuration ACK for batchId=" + batchId, e);
                    }
                }
            }
        }

        response.setStatus(HttpServletResponse.SC_OK);
        if (failedSubscribes == 0) {
            return HttpResponses.okJSON();
        } else {
            return HttpResponses.errorJSON("One or more event channel subscriptions were not successful. Check the server logs.");
        }
    }

    // Using a Servlet Filter for the async channel. We're doing this because we
    // do not want these requests making their way to Stapler. This is really
    // down to fear of the unknown magic that happens in Stapler and the effect
    // on it of changing requests from sync to async. The Filter is simple and
    // helps us to side-step any possible issues in Stapler.
    private static class SSEListenChannelFilter implements Filter {

        @Override
        public void init(FilterConfig filterConfig) throws ServletException {
        }

        @Override
        public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain filterChain) throws IOException, ServletException {
            if (servletRequest instanceof HttpServletRequest) {
                HttpServletRequest httpServletRequest = (HttpServletRequest) servletRequest;
                String requestedResource = getRequestedResourcePath(httpServletRequest);
                
                if (requestedResource.startsWith(SSE_LISTEN_URL_PREFIX)) {
                    HttpServletResponse httpServletResponse = (HttpServletResponse) servletResponse;
                    String clientId = requestedResource.substring(SSE_LISTEN_URL_PREFIX.length());
                    
                    clientId = URLDecoder.decode(clientId, "UTF-8");
                    EventDispatcherFactory.start(clientId, httpServletRequest, httpServletResponse);
                    return; // Do not allow this request on to Stapler
                }
            }
            filterChain.doFilter(servletRequest,servletResponse);
        }

        @Override
        public void destroy() {
        }
    }

    private static String getRequestedResourcePath(HttpServletRequest httpServletRequest) {
        return httpServletRequest.getRequestURI().substring(httpServletRequest.getContextPath().length());
    }

    private static class SubscriptionConfig {
        private String dispatcherId;
        private List<EventFilter> subscribeSet = Collections.emptyList();
        private List<EventFilter> unsubscribeSet = Collections.emptyList();
        private boolean unsubscribeAll = false;
        
        private static SubscriptionConfig fromRequest(StaplerRequest request) throws IOException {
            JSONObject payload = Util.readJSONPayload(request);
            SubscriptionConfig config = new SubscriptionConfig();
            
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
                        LOGGER.log(Level.SEVERE, "Invalid SSE payload. Expecting an array of JSON Objects for property " + key, e);
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
