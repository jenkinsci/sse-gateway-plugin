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
package org.jenkinsci.test.node;

import com.github.eirslett.maven.plugins.frontend.lib.FrontendPluginFactory;
import com.github.eirslett.maven.plugins.frontend.lib.TaskRunnerException;
import org.junit.Assert;
import org.jvnet.hudson.test.JenkinsRule;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Simple gulp runner.
 * <p>
 * Allows us to run gulp commands from inside tests e.g. to run integration tests
 * i.e. where we have a running Jenkins, and then we run some node code that can
 * executes against the running Jenkins. 
 * 
 * @author <a href="mailto:tom.fennelly@gmail.com">tom.fennelly@gmail.com</a>
 */
public class GulpRunner {
    
    // TODO: maybe move this out into a reusable component.

    private static File workDir;
    private JenkinsRule jenkinsRule = null;
    
    static {
        workDir = new File("./");
        if (!new File(workDir, "node").isDirectory()) {
            throw new IllegalStateException("Local/build env node is not yet installed. Run an mvn build; it will installed the required bits.");
        }
    }
    
    public GulpRunner() {
    }
    
    public GulpRunner(JenkinsRule jenkinsRule) {
        this.jenkinsRule = jenkinsRule;
    }
    
    public void run(String command) throws TaskRunnerException {
        FrontendPluginFactory frontendPluginFactory = new FrontendPluginFactory(workDir, workDir);
        Map<String, String> env = new HashMap<>();

        try {
            if (jenkinsRule != null) {
                env.put("JENKINS_URL", jenkinsRule.getURL().toString());
                env.put("JENKINS_HOME", jenkinsRule.jenkins.getRootDir().getAbsolutePath());
            }
        } catch (IOException e) {
            Assert.fail("Unexpected error: " + e.getMessage());
        }

        System.out.println("------------- GulpRunner <<Start>> -------------");
        System.out.println(" command: [" + command + "]");
        System.out.println(" env: ");
        System.out.println("     export JENKINS_URL=" + env.get("JENKINS_URL"));
        System.out.println("     export JENKINS_HOME=" + env.get("JENKINS_HOME"));
        System.out.println();
        frontendPluginFactory.getGulpRunner().execute(command, env);
        System.out.println("-------------- GulpRunner <<End>> --------------");
    }
    
    public void runIntegrationSpec(String specName) throws TaskRunnerException {
        run(String.format("test --test %s --testFileSuffix ispec", specName));
    }
}
