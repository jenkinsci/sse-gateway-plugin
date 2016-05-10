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
import hudson.util.HttpResponses;
import hudson.util.PluginServletFilter;
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
import java.io.IOException;
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
public class Endpoint implements RootAction {

    protected static final String SSE_GATEWAY_URL = "/sse-gateway";
    private static final Logger LOGGER = Logger.getLogger(Endpoint.class.getName());

    public Endpoint() throws ServletException {
        init();
    }

    protected void init() throws ServletException {
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
    
    @RequirePOST
    @Restricted(DoNotUse.class) // Web only
    public HttpResponse doConfigure(StaplerRequest request) throws IOException {
        HttpSession session = request.getSession();
        
        // We want to ensure that, at one time, only one set of configurations are being applied,
        // for a given user session. 
        synchronized (EventDispatcher.SSEHttpSessionListener.getSessionSyncObj(session)) {
            SubscriptionConfig subscriptionConfig = SubscriptionConfig.fromRequest(request);

            if (subscriptionConfig.dispatcherId != null && subscriptionConfig.hasConfigs()) {
                EventDispatcher dispatcher = EventDispatcherFactory.getDispatcher(subscriptionConfig.dispatcherId, request.getSession());

                if (dispatcher == null) {
                    throw new IOException("Failed Jenkins SSE Gateway configuration request. Unknown SSE event dispatcher " + subscriptionConfig.dispatcherId);
                }

                if (subscriptionConfig.unsubscribeAll) {
                    dispatcher.unsubscribeAll();
                }
                for (EventFilter filter : subscriptionConfig.unsubscribeSet) {
                    dispatcher.unsubscribe(filter);
                }
                for (EventFilter filter : subscriptionConfig.subscribeSet) {
                    dispatcher.subscribe(filter);
                }
            }
        }

        return HttpResponses.okJSON();
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
                
                if (requestedResource.equals(SSE_GATEWAY_URL + "/listen")) {
                    HttpServletResponse httpServletResponse = (HttpServletResponse) servletResponse;
                    EventDispatcherFactory.start(httpServletRequest, httpServletResponse);
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
            
            config.dispatcherId = payload.optString("dispatcher", null);
            if (config.dispatcherId != null) {
                config.subscribeSet = extractFilterSet(payload, "subscribe");
                config.unsubscribeSet = extractFilterSet(payload, "unsubscribe");
                if (config.unsubscribeSet.isEmpty()) {
                    String unsubscribe = payload.optString("unsubscribe", null);
                    if ("*".equals(unsubscribe) || "all".equalsIgnoreCase(unsubscribe)) {
                        config.unsubscribeAll = true;
                    }
                }
            } else {
                LOGGER.log(Level.SEVERE, "Received an SSE Gateway configuration request that did not contain a 'dispatcher' ID. Ignoring request.");
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
