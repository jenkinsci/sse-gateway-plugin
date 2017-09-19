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

import com.github.eirslett.maven.plugins.frontend.lib.TaskRunnerException;
import hudson.model.Job;
import org.jenkinsci.test.node.GulpRunner;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

import java.io.IOException;

/**
 * @author <a href="mailto:tom.fennelly@gmail.com">tom.fennelly@gmail.com</a>
 */
public class SSEPluginIntegrationTest {
    
    @Rule
    public JenkinsRule jenkins = new JenkinsRule();
    
    @Before
    public void setupRealm() {
        jenkins.jenkins.setSecurityRealm(jenkins.createDummySecurityRealm());
    }
    
    @Test
    public void test_no_filter() throws TaskRunnerException, IOException {
        // Create a job. We will trigger a build of the job inside
        // sse-plugin-no-filters-ispec.js (the integration test). That build execution
        // should trigger events to the event subscribers in the test.
        jenkins.createFreeStyleProject("sse-gateway-test-job");
        
        GulpRunner gulpRunner = new GulpRunner(jenkins);

        gulpRunner.runIntegrationSpec("sse-plugin-no-filter");
    }
    
    @Test
    public void test_with_filter() throws TaskRunnerException, IOException {
        // Create a job. We will trigger a build of the job inside
        // sse-plugin-with-filters-ispec.js (the integration test). That build execution
        // should trigger events to the event subscribers in the test.
        jenkins.createFreeStyleProject("sse-gateway-test-job");
        
        GulpRunner gulpRunner = new GulpRunner(jenkins);

        gulpRunner.runIntegrationSpec("sse-plugin-with-filter");
    }
    
    @Test
    public void test_store_and_forward() throws TaskRunnerException, IOException {
        // Create a job. We will trigger a build of the job inside
        // sse-plugin-store-and-forward.js (the integration test). That build execution
        // should trigger events to the event subscribers in the test.
        jenkins.createFreeStyleProject("sse-gateway-test-job");
        
        GulpRunner gulpRunner = new GulpRunner(jenkins);

        gulpRunner.runIntegrationSpec("sse-plugin-store-and-forward");
    }
}
