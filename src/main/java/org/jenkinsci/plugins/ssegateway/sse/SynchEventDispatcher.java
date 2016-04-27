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

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.Serializable;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Synchronous event dispatcher.
 * <p>
 * To support pre servlet 3.0.
 *     
 * @author <a href="mailto:tom.fennelly@gmail.com">tom.fennelly@gmail.com</a>
 */
class SynchEventDispatcher extends EventDispatcher implements Serializable {

    private static final long serialVersionUID = -1L;

    private static final Logger LOGGER = Logger.getLogger(SynchEventDispatcher.class.getName());

    private transient HttpServletResponse response;

    @Override
    void start(HttpServletRequest request, HttpServletResponse response) {
        this.response = response;
        LOGGER.log(Level.WARNING, "This servlet container does not support asynchronous requests. Servicing of Server Sent Events (SSE) may result in servlet request thread starvation. DO NOT use this in production!!!");
    }

    @Override
    HttpServletResponse getResponse() {
        return response;
    }
}