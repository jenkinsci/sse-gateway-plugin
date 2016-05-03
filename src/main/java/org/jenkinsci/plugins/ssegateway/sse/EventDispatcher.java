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
import jenkins.util.HttpSessionListener;

import javax.annotation.Nonnull;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import javax.servlet.http.HttpSessionEvent;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Serializable;

/**
 * @author <a href="mailto:tom.fennelly@gmail.com">tom.fennelly@gmail.com</a>
 */
public abstract class EventDispatcher implements Serializable {
    
    abstract void start(HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException;
    abstract HttpServletResponse getResponse();
    
    public boolean dispatchEvent(String name, String data) throws IOException, ServletException {
        HttpServletResponse response = getResponse();
        
        if (response == null) {
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
    
    public static void unsubscribeAll(@Nonnull HttpSession session) {
        
    }

    /**
     * Http session listener.
     */
    @Extension
    public static final class SSEHttpSessionListener extends HttpSessionListener {
        @Override
        public void sessionDestroyed(HttpSessionEvent httpSessionEvent) {
            unsubscribeAll(httpSessionEvent.getSession());
        }
    }
}