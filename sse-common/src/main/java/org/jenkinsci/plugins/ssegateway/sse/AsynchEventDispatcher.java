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

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import javax.servlet.AsyncContext;
import javax.servlet.AsyncEvent;
import javax.servlet.AsyncListener;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * @author <a href="mailto:tom.fennelly@gmail.com">tom.fennelly@gmail.com</a>
 */
class AsynchEventDispatcher extends EventDispatcher {

    private static final long serialVersionUID = -1L;

    private static final Logger LOGGER = Logger.getLogger(AsynchEventDispatcher.class.getName());
    
    // Set the timeout low so as to accommodate proxies like Nginx, which
    // kill the connection after e.g. 90 seconds. 30 seconds is the default
    // according to AsyncContext docs, so lets use that (explicitly).
    private static final long TIMEOUT = (1000 * 30);
    
    private transient AsyncContext asyncContext;
    @SuppressFBWarnings(value = "SE_TRANSIENT_FIELD_NOT_RESTORED", justification = "Doesn't make sense to persist it")
    private transient final Lock asyncContextLock = new ReentrantLock();

    @Override
    public void start(HttpServletRequest request, HttpServletResponse response) {
        final AsynchEventDispatcher dispatcher = this;
        
        asyncContextLock.lock();
        try {
            asyncContext = request.startAsync(request, response);
            asyncContext.setTimeout(TIMEOUT);
            asyncContext.addListener(new AsyncListener() {
                @Override
                public void onTimeout(AsyncEvent event) throws IOException {
                    asyncContextLock.lock();
                    try {
                        LOGGER.log(Level.FINE, "Async dispatcher 'onTimeout' event: {0}", dispatcher);
                        if (event.getAsyncContext() == asyncContext) {
                            // nulling asyncContext will force messages to the retry
                            // queue until we restart the connection.
                            asyncContext = null;
                        }
                        event.getAsyncContext().complete();
                    } finally {
                        asyncContextLock.unlock();
                    }
                }
                @Override
                public void onStartAsync(AsyncEvent event) throws IOException {
                    LOGGER.log(Level.FINE, "Async dispatcher 'onStartAsync' event: {0}", dispatcher);
                }
                @Override
                public void onError(AsyncEvent event) throws IOException {
                    LOGGER.log(Level.WARNING, "Async dispatcher 'onError' event: {0}", dispatcher);
                }
                @Override
                public void onComplete(AsyncEvent event) throws IOException {
                    LOGGER.log(Level.FINE, "Async dispatcher 'onComplete' event: {0}", dispatcher);
                }
            });
        } finally {
            asyncContextLock.unlock();
        }
    }

    @Override
    public HttpServletResponse getResponse() {
        if (asyncContext == null) {
            return null;
        }
        return (HttpServletResponse) asyncContext.getResponse();
    }

    @Override
    public void stop() {
        asyncContext.complete();
    }
}