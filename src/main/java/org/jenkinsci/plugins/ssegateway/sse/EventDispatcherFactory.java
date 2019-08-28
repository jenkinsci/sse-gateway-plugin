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

import hudson.security.csrf.CrumbIssuer;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import org.jenkinsci.plugins.ssegateway.Util;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;

/**
 * @author <a href="mailto:tom.fennelly@gmail.com">tom.fennelly@gmail.com</a>
 */
@Restricted(NoExternalUse.class)
public class EventDispatcherFactory {

    private static final Logger LOGGER = LoggerFactory.getLogger( EventDispatcherFactory.class.getName());

    public static final String DISPATCHER_SESSION_KEY = EventDispatcher.class.getName();
    
    private static Class<? extends EventDispatcher> runtimeClass;
    
    static {
        try {
            if (isAsyncSupported()) {
                runtimeClass = (Class<? extends EventDispatcher>) Class.forName(EventDispatcherFactory.class.getPackage().getName() + ".AsynchEventDispatcher");
            } else {
                runtimeClass = (Class<? extends EventDispatcher>) Class.forName(EventDispatcherFactory.class.getPackage().getName() +   ".SynchEventDispatcher");
            }
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("Unexpected Exception.", e);
        }
    }
    
    public static EventDispatcher start(@Nonnull String clientId, @Nonnull HttpServletRequest request, @Nonnull HttpServletResponse response) {
        try {
            HttpSession session = request.getSession();
            EventDispatcher dispatcher = EventDispatcherFactory.getDispatcher(clientId, session);
            
            if (dispatcher == null) {
                LOGGER.debug(String.format("Unknown dispatcher client Id '%s' on HTTP session '%s'. Creating a new one. " +
                        "Make sure you are calling 'connect' before 'listen' and that HTTP sessions are being maintained between 'connect' and 'configure' calls. " +
                        "SSE client reconnects will not work - probably fine if running in non-browser/test mode.", clientId, session.getId()));
                dispatcher = EventDispatcherFactory.newDispatcher(clientId, session);
            }

            dispatcher.start(request, response);
            dispatcher.setDefaultHeaders();

            JSONObject openData = new JSONObject();

            openData.put("dispatcherId", dispatcher.getId());
            openData.put("dispatcherInst", System.identityHashCode(dispatcher));

            if (Util.isTestEnv()) {
                openData.putAll(Util.getSessionInfo(session));

                // Crumb needed for testing because we use it to fire off some
                // test builds via the POST API.
                Jenkins jenkins = Jenkins.getInstance();
                CrumbIssuer crumbIssuer = jenkins.getCrumbIssuer();
                if (crumbIssuer != null) {
                    JSONObject crumb = new JSONObject();
                    crumb.put("name", crumbIssuer.getDescriptor().getCrumbRequestField());
                    crumb.put("value", crumbIssuer.getCrumb(request));
                    openData.put("crumb", crumb);
                } else {
                    LOGGER.warn("No CrumbIssuer on Jenkins instance. Some POSTs might not work.");
                }
            }

            dispatcher.dispatchEvent("open", openData.toString());

            // Run the retry process in case this is a reconnect.
            dispatcher.processRetries();

            return dispatcher;
        } catch (Exception e) {
            throw new IllegalStateException("Unexpected Exception.", e);
        }
    }

    /**
     * Get the session {@link EventDispatcher}s from the {@link HttpSession}.
     *     
     * @param session The {@link HttpSession}.
     * @return The session {@link EventDispatcher}s.
     */
    public synchronized static Map<String, EventDispatcher> getDispatchers(@Nonnull HttpSession session) {
        Map<String, EventDispatcher> dispatchers = (Map<String, EventDispatcher>) session.getAttribute(DISPATCHER_SESSION_KEY);
        if (dispatchers == null) {
            dispatchers = new HashMap<>();
            session.setAttribute(DISPATCHER_SESSION_KEY, dispatchers);
        }
        return dispatchers;
    }

    /**
     * Create a new {@link EventDispatcher} instance and attach it to the user session. 
     *     
     * @param clientId The dispatcher client Id.
     * @param session The {@link HttpSession}.
     * @return The new {@link EventDispatcher} instance.
     */
    public synchronized static EventDispatcher newDispatcher(@Nonnull String clientId, @Nonnull HttpSession session) {
        Map<String, EventDispatcher> dispatchers = getDispatchers(session);
        try {
            EventDispatcher dispatcher = runtimeClass.newInstance();
            dispatcher.setId(clientId);
            dispatchers.put(clientId, dispatcher);
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug(String.format("New dispatcher '%s' attached to HTTP session '%s'.", dispatcher, session.getId()));
            }
            return dispatcher;
        } catch (Exception e) {
            throw new IllegalStateException("Unexpected Exception.", e);
        }
    }

    /**
     * Get the specified {@link EventDispatcher} instance from the {@link HttpSession}.
     * @param dispatcherId The dispatcher ID.
     * @param session The {@link HttpSession}.
     * @return The {@link EventDispatcher}, or {@code null} if no such dispatcher is known. 
     */
    public static @CheckForNull EventDispatcher getDispatcher(@Nonnull String dispatcherId, @Nonnull HttpSession session) {
        Map<String, EventDispatcher> dispatchers = getDispatchers(session);
        return dispatchers.get(dispatcherId);
    }

    private static boolean isAsyncSupported() {
        // We can use a system property for test overriding.
        String asyncSupportedProp = System.getProperty("jenkins.eventbus.web.asyncSupported");
        if (asyncSupportedProp != null) {
            return asyncSupportedProp.equals("true");
        }
        
        try {
            HttpServletRequest.class.getMethod("startAsync");
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }
}
