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

import org.jenkinsci.plugins.pubsub.PubsubBus;
import org.jenkinsci.test.node.GulpRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;

import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * @author <a href="mailto:tom.fennelly@gmail.com">tom.fennelly@gmail.com</a>
 */
@WithJenkins
class SSEPluginIntegrationTest {

    private JenkinsRule j;

    @BeforeEach
    void setUp(JenkinsRule rule) {
        j = rule;
    }

    @BeforeEach
    void setupRealm() {
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        PubsubBus.getBus().start();
    }

    @Test
    void test_no_filter() throws Exception {
        // Create a job. We will trigger a build of the job inside
        // sse-plugin-no-filters-ispec.js (the integration test). That build execution
        // should trigger events to the event subscribers in the test.
        j.createFreeStyleProject("sse-gateway-test-job");

        GulpRunner gulpRunner = new GulpRunner(j);

        gulpRunner.runIntegrationSpec("sse-plugin-no-filter");
    }

    @Test
    void test_with_filter() throws Exception {
        // Create a job. We will trigger a build of the job inside
        // sse-plugin-with-filters-ispec.js (the integration test). That build execution
        // should trigger events to the event subscribers in the test.
        j.createFreeStyleProject("sse-gateway-test-job");

        GulpRunner gulpRunner = new GulpRunner(j);

        gulpRunner.runIntegrationSpec("sse-plugin-with-filter");
    }

    @Test
    void test_store_and_forward() throws Exception {
        // Create a job. We will trigger a build of the job inside
        // sse-plugin-store-and-forward.js (the integration test). That build execution
        // should trigger events to the event subscribers in the test.
        j.createFreeStyleProject("sse-gateway-test-job");

        GulpRunner gulpRunner = new GulpRunner(j);

        gulpRunner.runIntegrationSpec("sse-plugin-store-and-forward");
    }

    @Test
    void test_retryqueue_timeout() throws Exception {
        // Create a job. We will trigger a build of the job inside
        // sse-plugin-retryqueue-timeout.js (the integration test). That build execution
        // should trigger events to the event subscribers in the test.
        j.createFreeStyleProject("sse-gateway-test-job");

        // set lifetime for retry events to 15 sec - default is 300 sec
        // in the integration test waiting time is 20000ms until restarting the proxy
        // -> all queued event should be removed from the queue
        long saveEventLifetime = org.jenkinsci.plugins.ssegateway.sse.EventDispatcher.RETRY_QUEUE_EVENT_LIFETIME;
        org.jenkinsci.plugins.ssegateway.sse.EventDispatcher.RETRY_QUEUE_EVENT_LIFETIME = 15;

        // set delay for retry loop = 500ms - default is 100ms
        long saveProcessingDelay = org.jenkinsci.plugins.ssegateway.sse.EventDispatcher.RETRY_QUEUE_PROCESSING_DELAY;
        org.jenkinsci.plugins.ssegateway.sse.EventDispatcher.RETRY_QUEUE_PROCESSING_DELAY = 500;

        GulpRunner gulpRunner = new GulpRunner(j);

        gulpRunner.runIntegrationSpec("sse-plugin-retryqueue-timeout");

        // restore saved values
        org.jenkinsci.plugins.ssegateway.sse.EventDispatcher.RETRY_QUEUE_EVENT_LIFETIME = saveEventLifetime;
        org.jenkinsci.plugins.ssegateway.sse.EventDispatcher.RETRY_QUEUE_PROCESSING_DELAY = saveProcessingDelay;
    }

}
