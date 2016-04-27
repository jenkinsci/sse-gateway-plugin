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
import hudson.util.PluginServletFilter;
import org.jenkinsci.plugins.ssegateway.sse.EventDispatcher;
import org.jenkinsci.plugins.ssegateway.sse.EventDispatcherFactory;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * @author <a href="mailto:tom.fennelly@gmail.com">tom.fennelly@gmail.com</a>
 */
@Extension
public class Endpoint implements RootAction {

    private static final String SSE_GATEWAY_URL = "/sse-gateway";

    public Endpoint() throws ServletException {
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
    
    // Using a Servlet Filter for the async channel. We're doing this because we
    // do not want these requests making their way to Stapler. This is really
    // down to fear of the unknown magic that happens in Stapler and the effect
    // on it of changing requests from sync to async. The Filter is simple and
    // helps us to side-step any possible issues in Stapler.
    private class SSEListenChannelFilter implements Filter {

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
                    final EventDispatcher dispatcher = EventDispatcherFactory.start(httpServletRequest, httpServletResponse);
                    
                    new Thread() {
                        @Override
                        public void run() {
                            for (int i = 0; i < 10000; i++) {
                                try {
                                    Thread.sleep(1000);
                                    dispatcher.dispatchEvent("message", ("message-" + i));
                                } catch (InterruptedException e) {
                                    e.printStackTrace();
                                } catch (ServletException e) {
                                    e.printStackTrace();
                                } catch (IOException e) {
                                    e.printStackTrace();
                                }
                            }
                        }
                    }.start();
                    
                    return; // Do not allow this request on to Stapler
                }
            }
            filterChain.doFilter(servletRequest,servletResponse);
        }

        @Override
        public void destroy() {
        }
    }

    private String getRequestedResourcePath(HttpServletRequest httpServletRequest) {
        return httpServletRequest.getRequestURI().substring(httpServletRequest.getContextPath().length());
    }
}
