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

/*
 * Integration test.
 * <p>
 * Run from Java (via GulpRunner) with a running Jenkins (via JenkinsRule).
 */

var jsTest = require('./jsTest');
var url = require('url');
var ajax = require('../../main/js/ajax');
var os = require('os');

describe("sse plugin integration tests - ", function () {

    beforeEach(function() {
        originalJasmineTimeout = jasmine.DEFAULT_TIMEOUT_INTERVAL;
        jasmine.DEFAULT_TIMEOUT_INTERVAL = 30000;
    });

    it("- retry queue timeout", function (done) {
        
        //
        // The gist of what happend in tis test...
        //
        // The jenkins instance is running as normal from the enclosing junit test class.
        // In here then, we launch a http-proxy and connect the SSE client under test
        // through the http-proxy. Then, we kill the proxy, which causes the sse client to
        // lose it's connection to the server. Then, we fire off a build while the 
        // connection is lost. This should cause the dispatcher (on the server side) to
        // fail to push the SSE events, which should result in them being added to the
        // retry queue in the dispatcher.
        // Now we wait until the retry queue event lifetime has passed.
        // Then, when we restart the proxy, the sse client should auto-reconnect but there
        // should no event arrive into the client.
        //
        
        var jenkinsUrl = process.env.JENKINS_URL;
        var parsedJenkinsUrl = url.parse(jenkinsUrl);
        var jenkinsPort = parsedJenkinsUrl.port;
        var content = '<html><head data-rooturl="@JENKINS_URL@" data-resurl="@JENKINS_URL@/static/908d75c1" data-adjuncturl="@JENKINS_URL@/adjuncts/908d75c1"></head><body></body></html>'
            .replace('@JENKINS_URL@', 'http://localhost:18080/jenkins/');

        var firstSubscribe = true;

        jsTest.onPage(function() {
            var proxy;

            // Launching the http-proxy in a separate process completely because
            // it's easier to "kill" it that way. Yes, we could embed the proxy here
            // but then it's not possible to kill it as part of the test.
            function startProxy(onStart) {
                var childProcess = require('child_process');
                proxy = childProcess.fork('./src/test/js/httpproxy.js', [jenkinsPort.toString()], { stdio: 'inherit' });
                proxy.on('message', function (message) {
                    if (message.started && onStart) {
                        onStart();
                    }
                });
            }
            function stopProxy(onStop) {
                proxy.on('close', function() {
                    if (onStop) {
                        onStop();
                    }
                });
                if(os.platform() === 'win32'){
                    proxy.kill('SIGKILL');
                }else{
                    proxy.kill('SIGHUP');
                }
            }
            
            startProxy(function() {
                console.log('** proxy started');
                var job_run_ended_received = false;
                var sseClient = require('../../../headless-client');
                var sseConnection = sseClient.connect('sse-client-123', function(jenkinsSessionInfo) {
                    function build(jenkinsSessionInfo) {
                        ajax.post(undefined, jenkinsUrl + 'job/sse-gateway-test-job/build', jenkinsSessionInfo);
                    }
                
                    var eventCount      = 0;
                    var eventRetryCount = 0;
                    if (firstSubscribe) {
                        sseConnection.subscribe({
                            channelName: 'job',
                            onSubscribed: function() {
                                // Once subscribed to the job channel, kill the proxy.
                                // Wait for a moment before doing this however because
                                // the configure request might not yet be fully completed.
                                if (firstSubscribe) {
                                    firstSubscribe = false;
                                    setTimeout(function() {
                                        stopProxy(function() {
                                            console.log('** proxy stopped.');

                                            // Now lets fire off a build of the sample job
                                            console.log('** start build of sample job.');
                                            build(jenkinsSessionInfo);

                                            // After a few seconds (>RETRY_QUEUE_EVENT_LIFETIME),
                                            // lets restart the proxy.
                                            // Once we do that, no events are allowed.
                                            // All events missed while the connection was lost
                                            // (when the proxy was down) are discarded since the
                                            // first event in the Retryqueue has exceeded the lifetime.
                                            setTimeout(function () {
                                                console.log('** Restarting the proxy.');
                                                startProxy();
                                                setTimeout(function () {
                                                    console.log('** after some more time -  check if there are messages');
                                                    console.log('**** eventCount: ' + eventCount.toString());
                                                    console.log('**** eventRetryCount: ' + eventRetryCount.toString());
                                                    expect(eventCount === 0).toBe(true);
                                                    expect(eventRetryCount === 0).toBe(true);

                                                    // We're done !!!
                                                    sseConnection.disconnect();
                                                    setTimeout(function () {
                                                        stopProxy();
                                                    }, 1000);
                                                    console.log('** done');
                                                    done();
                                                }, 5000)  // give jenkins some time to send events - if there are any in the retry queue
                                            }, 20000);  // > RETRY_QUEUE_EVENT_LIFETIME, set to '15' in SSEPluginIntegrationTest.java
                                        });
                                    }, 1000);
                                }
                            },
                            onEvent: function(event) {
                                eventCount++;
                                if (event.sse_dispatch_retry) {
                                    eventRetryCount++;
                                }
                                if (!job_run_ended_received && (event.jenkins_event === 'job_run_ended')) {
                                    job_run_ended_received = true;
                                    console.log('** \'job_run_ended\' event received.');

                                    // No events allowed.
                                    // Retryqueue event timeout has occured before
                                    // restarting the proxy
                                    console.log('** eventCount: ' + eventCount.toString());
                                    console.log('** eventRetryCount: ' + eventRetryCount.toString());
                                    expect(eventCount === 0).toBe(true);
                                    expect(eventRetryCount === 0).toBe(true);

                                    // We're done !!!
                                    sseConnection.disconnect();
                                    setTimeout(function () {
                                        stopProxy();
                                    }, 1000);
                                    console.log('** job_run_ended - done');
                                    done();
                                }
                            }
                        });
                    }
                });
            });
        }, content);

    }, 90000);

    afterEach(function() {
        jasmine.DEFAULT_TIMEOUT_INTERVAL = originalJasmineTimeout;
    });
});
