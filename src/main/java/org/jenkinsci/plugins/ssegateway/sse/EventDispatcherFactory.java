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

import hudson.Functions;
import hudson.security.csrf.CrumbIssuer;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * @author <a href="mailto:tom.fennelly@gmail.com">tom.fennelly@gmail.com</a>
 */
@Restricted(NoExternalUse.class)
public class EventDispatcherFactory {

    private static final Logger LOGGER = Logger.getLogger(EventDispatcherFactory.class.getName());

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
    
    public static EventDispatcher start(HttpServletRequest request, HttpServletResponse response) {
        try {
            HttpSession session = request.getSession();
            EventDispatcher instance = newDispatcher(session);
            Jenkins jenkins = Jenkins.getInstance();

            instance.start(request, response);
            instance.setDefaultHeaders();

            JSONObject openData = new JSONObject();

            openData.put("dispatcher", instance.getId());
            
            if (Functions.getIsUnitTest()) {
                openData.put("sessionid", session.getId());
                openData.put("cookieName", session.getServletContext().getSessionCookieConfig().getName());

                // Crumb needed for testing because we use it to fire off some 
                // test builds via the POST API.
                CrumbIssuer crumbIssuer = jenkins.getCrumbIssuer();
                if (crumbIssuer != null) {
                    JSONObject crumb = new JSONObject();
                    crumb.put("name", crumbIssuer.getDescriptor().getCrumbRequestField());
                    crumb.put("value", crumbIssuer.getCrumb(request));
                    openData.put("crumb", crumb);
                } else {
                    // 
                    LOGGER.log(Level.WARNING, "Cannot support SSE Gateway client. No CrumbIssuer on Jenkins instance.");
                }
            }
            
            instance.dispatchEvent("open", openData.toString());
            
            return instance;
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
     * @param session The {@link HttpSession}.
     * @return The new {@link EventDispatcher} instance.
     */
    public synchronized static EventDispatcher newDispatcher(@Nonnull HttpSession session) {
        Map<String, EventDispatcher> dispatchers = getDispatchers(session);
        try {
            EventDispatcher dispatcher = runtimeClass.newInstance();
            dispatchers.put(dispatcher.getId(), dispatcher);
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