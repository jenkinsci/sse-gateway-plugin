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
package com.cloudbees.analytics.sse;

import com.google.common.collect.ImmutableMap;
import net.sf.json.JSONObject;
import org.acegisecurity.Authentication;
import org.acegisecurity.adapters.PrincipalAcegiUserToken;
import org.apache.commons.lang.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jenkinsci.plugins.ssegateway.SubscriptionConfigQueue;
import org.jenkinsci.plugins.ssegateway.sse.EventDispatcherFactory;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.security.Principal;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Standalone SSE Servlet based on the Jenkins <a href="https://github.com/jenkinsci/sse-gateway-plugin/">sse-gateway-plugin</a>.
 */
public class SseServlet extends HttpServlet {
    private static final long serialVersionUID = 1145649854458913711L;
    
    private static final Logger LOGGER = LogManager.getLogger(SseServlet.class);

    private static final String CONNECT_PATH = "/connect";
    private static final String CONFIGURE_PATH = "/configure";
    private static final String LISTEN_PATH = "/listen";
    private static final String PING_PATH = "/ping";

    private static final String OK = "OK";

    private final transient SseService sseService;

    public SseServlet() {
        sseService = new SseService();
        sseService.init();
    }

    /**
     * GET endpoints:
     * <p>
     * <ul>
     * <li>/sse-gateway/connect</li>
     * <li>/sse-gateway/listen</li>
     * <li>/sse-gateway/ping</li>
     * </ul>
     */
    @Override
    protected void doGet(final HttpServletRequest request, final HttpServletResponse response) throws ServletException, IOException {
        LOGGER.debug("doGet - called with requestUrl={}", request.getRequestURL().toString());
        processGet(request, response);
    }

    private void processGet(final HttpServletRequest request, final HttpServletResponse response) throws ServletException, IOException {
        if (pathMatches(request.getPathInfo(), CONNECT_PATH)) {
            doConnect(request, response);
        } else if (pathMatches(request.getPathInfo(), LISTEN_PATH + "/[\\w-]+")) {
            doListen(request, response);
        } else if (pathMatches(request.getPathInfo(), PING_PATH)) {
            doPing(request, response);
        } else {
            errorResponse(response, HttpServletResponse.SC_BAD_REQUEST, "invalid or missing path, path=" + request.getPathInfo());
        }

    }

    /**
     * POST endpoints:
     * <p>
     * <ul>
     * <li>/sse-gateway/configure</li>
     * </ul>
     */
    @Override
    protected void doPost(final HttpServletRequest request, final HttpServletResponse response) throws ServletException, IOException {
        processPost(request, response);
    }

    private void processPost(final HttpServletRequest request, final HttpServletResponse response) throws IOException {
        if (pathMatches(request.getPathInfo(), CONFIGURE_PATH)) {
            doConfigure(request, response);
        } else {
            errorResponse(response, HttpServletResponse.SC_BAD_REQUEST, "invalid path, path=" + request.getPathInfo());
        }
    }

    private boolean pathMatches(final String pathInfo, final String patternString) {
        final Pattern pattern = Pattern.compile(patternString);
        return pathInfo != null && pattern.matcher(pathInfo).matches();
    }

    /**
     * Creates an SSE event dispatcher and associates it with the current session and clientId/dispatcherId.
     */
    private void doConnect(final HttpServletRequest request, final HttpServletResponse response) {
        LOGGER.debug("doConnect - called with requestUrl={}, jsessionid={}", request.getRequestURL().toString(), request.getSession().getId());
        try {
            Authentication authentication = getAuthentication(request);
            sseService.initDispatcher(request, response, authentication);
            okResponse(response, ImmutableMap.of("jsessionid", request.getSession().getId()));
        } catch (final IOException e) {
            errorResponse(response, HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
        }
    }

    private Authentication getAuthentication(final HttpServletRequest request) {
        final Principal userPrincipal = request.getUserPrincipal();
        return new PrincipalAcegiUserToken(UUID.randomUUID().toString(), null, null, null, userPrincipal);
    }

    /**
     * Subscribes/unsubscribes named channel subscriptions to the event dispatchers associated with the current session and clientId/dispatcherId.
     */
    private void doConfigure(final HttpServletRequest request, final HttpServletResponse response) throws IOException {
        LOGGER.debug("doConfigure - called with requestUrl={}", request.getRequestURL().toString());

        final SubscriptionConfigQueue.SubscriptionConfig subscriptionConfig = SubscriptionConfigQueue.SubscriptionConfig.fromRequest(request);

        final boolean validConfig = sseService.validateSubscriptionConfig(subscriptionConfig);
        final boolean queuedOkay = sseService.enqueueSubscriptionConfig(subscriptionConfig);

        if (validConfig && queuedOkay) {
            okResponse(response, OK);
            return;
        } else if (!validConfig) {
            if (subscriptionConfig.getDispatcherId() == null) {
                errorResponse(response, HttpServletResponse.SC_BAD_REQUEST, "'dispatcherId' not specified.");
                return;
            } else if (!subscriptionConfig.hasConfigs()) {
                errorResponse(response, HttpServletResponse.SC_BAD_REQUEST, "No 'subscribe' or 'unsubscribe' configurations provided in configuration request.");
                return;
            }
        }

        errorResponse(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Unable to process channel subscription request at this time.");
    }

    /**
     * Dispatchers a simple message to any listening clients associated with this session and clientId/dispatcherId is configured correctly, independent of any channel subscriptions.
     */
    private void doPing(final HttpServletRequest request, final HttpServletResponse response) throws IOException {
        LOGGER.debug("doPing - called with requestUrl={}", request.getRequestURL().toString());

        final boolean success = sseService.ping(request);

        if (!success) {
            errorResponse(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Failed to send pingback to dispatcher " + request.getParameter("dispatcherId") + ".");
            return;
        }
        okResponse(response, OK);
    }

    /**
     * Opens a http request for the given clientId/dispatcherId, and listens for messages indefinitely.
     */
    private void doListen(final HttpServletRequest request, final HttpServletResponse response) {
        final List<String> paths = getUrlPaths(request);

        LOGGER.debug("doListen - called with requestUrl={}, clientTokensParam={}", request.getRequestURL().toString(), paths.get(paths.size() - 1));

        final String clientTokensPathParam = paths.get(paths.size() - 1);
        final String[] clientTokens = clientTokensPathParam.split(";");
        String clientId = clientTokens[0];

        // If there's a second token it would be the jsessionid for
        // when we're using a headless client. Not needed here though,
        // so just stripping out the clientId part.

        try {
            clientId = URLDecoder.decode(clientId, "UTF-8");
        } catch (final UnsupportedEncodingException e) {
            errorResponse(response, HttpServletResponse.SC_BAD_REQUEST, "invalid clientId=" + clientId);
            return;
        }

        response.setContentType("text/event-stream");
        response.setHeader("Connection", "keep-alive");
        response.setHeader("Transfer-Encoding", "chunked");

        Authentication authentication = getAuthentication(request);
        EventDispatcherFactory.start(clientId, request, response, authentication, null);
        noContentResponse(response);
    }

    private List<String> getUrlPaths(final HttpServletRequest request) {
        final String pathInfo = request.getPathInfo();
        final String[] paths = StringUtils.split(pathInfo, "/");
        return Arrays.asList(paths);
    }

    private void writeResponse(final HttpServletResponse response, final int status, final String body) {
        try {
            response.setStatus(status);
            response.getWriter().write(body);
        } catch (final IOException e) {
            LOGGER.error("error writing to http response, status={}, body={}", status, body, e);
        }
    }

    private void okResponse(final HttpServletResponse response, final String body) {
        writeResponse(response, 200, body);
    }

    private void okResponse(final HttpServletResponse response, final Map<String, String> jsonMap) {
        final JSONObject jsonObject = JSONObject.fromObject(jsonMap);
        writeResponse(response, 200, jsonObject.toString());
    }

    private void noContentResponse(final HttpServletResponse response) {
        response.setStatus(HttpServletResponse.SC_NO_CONTENT);
    }

    /**
     * Builds a json error response identical to the one used by Stapler (ie. sse-gateway-plugin) to keep it consistent for the client.
     */
    private void errorResponse(final HttpServletResponse response, final Integer status, final String message) {
        final Map<String, String> errorObject = ImmutableMap.of("status", "error", "message", message);
        final JSONObject errorJson = JSONObject.fromObject(errorObject);
        writeResponse(response, status, errorJson.toString());
    }
}
