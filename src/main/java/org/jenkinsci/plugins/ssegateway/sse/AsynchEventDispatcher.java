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

import javax.servlet.AsyncContext;
import javax.servlet.AsyncEvent;
import javax.servlet.AsyncListener;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.Serializable;

/**
 * @author <a href="mailto:tom.fennelly@gmail.com">tom.fennelly@gmail.com</a>
 */
class AsynchEventDispatcher extends EventDispatcher {

    private static final long serialVersionUID = -1L;
    
    private transient AsyncContext asyncContext;

    @Override
    void start(HttpServletRequest request, HttpServletResponse response) {
        asyncContext = request.startAsync();
        asyncContext.setTimeout(-1); // No timeout
        asyncContext.addListener(new AsyncListener() {
            @Override
            public void onTimeout(AsyncEvent event) throws IOException {
                event.getAsyncContext().complete();
            }
            @Override
            public void onStartAsync(AsyncEvent event) throws IOException {
            }
            @Override
            public void onError(AsyncEvent event) throws IOException {
            }
            @Override
            public void onComplete(AsyncEvent event) throws IOException {
                asyncContext = null;
            }
        });
    }

    @Override
    HttpServletResponse getResponse() {
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