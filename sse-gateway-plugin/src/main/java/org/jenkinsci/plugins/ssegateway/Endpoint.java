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

import com.google.common.collect.ImmutableMap;
import hudson.Extension;
import hudson.model.RootAction;
import hudson.security.csrf.CrumbExclusion;
import hudson.util.HttpResponses;
import hudson.util.PluginServletFilter;
import jenkins.model.Jenkins;
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
import java.io.File;
import java.io.IOException;
import java.net.URLDecoder;
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

    private SseServletBase servletBase;

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
                EventHistoryStore.setHistoryRoot(new File(jenkins.getRootDir(), "/logs/sse-events"));
                EventHistoryStore.enableAutoDeleteOnExpire();
            } catch (IOException e) {
                LOGGER.log(Level.SEVERE, "Unexpected error setting EventHistoryStore event history root dir.", e);
            }
        }
        PluginServletFilter.addFilter(new SSEListenChannelFilter());
        servletBase = new SseServletBase();
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
        servletBase.initDispatcher(request, response);
        return HttpResponses.okJSON(ImmutableMap.of("jsessionid", request.getSession().getId()));
    }

    @RequirePOST
    @Restricted(DoNotUse.class) // Web only
    public HttpResponse doConfigure(StaplerRequest request, StaplerResponse response) throws IOException {
        SubscriptionConfigQueue.SubscriptionConfig subscriptionConfig = SubscriptionConfigQueue.SubscriptionConfig.fromRequest(request);

        final boolean validConfig = servletBase.validateSubscriptionConfig(subscriptionConfig);
        final boolean queuedOkay = servletBase.enqueueSubscriptionConfig(subscriptionConfig);

        if (validConfig && queuedOkay) {
            response.setStatus(HttpServletResponse.SC_OK);
            return HttpResponses.okJSON();
        } else if(!validConfig) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            if (subscriptionConfig.getDispatcherId() == null) {
                return HttpResponses.errorJSON("'dispatcherId' not specified.");
            } else if (!subscriptionConfig.hasConfigs()) {
                return HttpResponses.errorJSON("No 'subscribe' or 'unsubscribe' configurations provided in configuration request.");
            }
        }

        response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        return HttpResponses.errorJSON("Unable to process channel subscription request at this time.");
    }

    @Restricted(DoNotUse.class) // Web only
    public HttpResponse doPing(StaplerRequest request) throws IOException {
        final boolean success = servletBase.ping(request);

        if(!success) {
            return HttpResponses.errorJSON("Failed to send pingback to dispatcher " + request.getParameter("dispatcherId") + ".");
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
            SubscriptionConfigQueue.start();
        }

        @Override
        public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain filterChain) throws IOException, ServletException {
            if (servletRequest instanceof HttpServletRequest) {
                HttpServletRequest httpServletRequest = (HttpServletRequest) servletRequest;
                String requestedResource = getRequestedResourcePath(httpServletRequest);

                if (requestedResource.startsWith(SSE_LISTEN_URL_PREFIX)) {
                    HttpServletResponse httpServletResponse = (HttpServletResponse) servletResponse;
                    String[] clientTokens = requestedResource.substring(SSE_LISTEN_URL_PREFIX.length()).split(";");
                    String clientId = clientTokens[0];

                    // If there's a second token it would be the jsessionid for 
                    // when we're using a headless client. Not needed here though,
                    // so just stripping out the clientId part.

                    clientId = URLDecoder.decode(clientId, "UTF-8");
                    EventDispatcherFactory.start(clientId, httpServletRequest, httpServletResponse);
                    return; // Do not allow this request on to Stapler
                }
            }
            filterChain.doFilter(servletRequest,servletResponse);
        }

        @Override
        public void destroy() {
            SubscriptionConfigQueue.stop();
        }
    }

    private static String getRequestedResourcePath(HttpServletRequest httpServletRequest) {
        return httpServletRequest.getRequestURI().substring(httpServletRequest.getContextPath().length());
    }

    public SseServletBase getServletBase() {
        return servletBase;
    }

    public void setServletBase(SseServletBase servletBase) {
        this.servletBase = servletBase;
    }
}
