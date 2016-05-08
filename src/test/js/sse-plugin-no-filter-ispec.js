/*
 * Integration test.
 * <p>
 * Run from Java (via GulpRunner) with a running Jenkins (via JenkinsRule).
 */

var jsTest = require('@jenkins-cd/js-test');
var waitUntil = require('wait-until-promise').default;

global.XMLHttpRequest = require("xmlhttprequest").XMLHttpRequest;

describe("sse plugin integration tests - subscribe and unsubscribe - no filters", function () {

    it("- test build receives events", function (done) {
        jsTest.onPage(function() {
            window.EventSource = require('eventsource');

            var api = jsTest.requireSrcModule('sse-client');
            var jenkinsSessionInfo;

            function runBuild() {
                var ajax = jsTest.requireSrcModule('ajax');
                ajax.post(undefined, api.jenkinsUrl + 'job/sse-gateway-test-job/build', jenkinsSessionInfo);
            }

            api.connect(function(jenkinsSession) {
                jenkinsSessionInfo = jenkinsSession;
            });

            // Listen for sse events and use them to determine
            // if subscribe/unsubscribe is working properly.
            var sseEvents = [];
            var subscribeCount = 0;
            var unsubscribeCount = 0;
            api.subscribe('sse', function (event) {
                sseEvents.push(event);
                if (event.jenkins_event === 'subscribe') {
                    subscribeCount++;
                } else if (event.jenkins_event === 'unsubscribe') {
                    unsubscribeCount++;
                }
            });
            
            // jobsStarted should eventually equal number of jobs started i.e. 2
            var jobsStarted = 0;
            api.subscribe('job', function (event) {
                // We actually wait for the end event before counting it.
                // The jobSubs listener (below) uses the start event to count.
                // This combo makes sure we are seeing thing in the right order.
                if (event.jenkins_event === 'job_run_ended') {
                    jobsStarted++;
                }
            });
            
            // jobSubsCalled should only hit 1 because we unsubscribe jobSubs 
            // before starting the second job.
            var jobSubsCalled = 0; 
            var jobSubs = api.subscribe('job', function (event) {
                if (event.jenkins_event === 'job_run_started') {
                    // Test unsubscribe now by unsubscribing jobSubs.
                    // See the waitUntil below.
                    api.unsubscribe(jobSubs);

                    jobSubsCalled++;

                    // Run the second build
                    runBuild();

                    // wait 60s for the second build and jobsStarted
                    // to equal 2
                    waitUntil(function () {
                        return jobsStarted === 2;
                    }, 60000).then(function() {
                        // The check everything, especially that the unsubscribe worked
                        // i.e. that this callback was not called again after.

                        // 3 subscribe events and 1 unsubscribe
                        expect(sseEvents.length).toBe(4);
                        expect(subscribeCount).toBe(3);
                        expect(unsubscribeCount).toBe(1);

                        // jobSubsCalled should only be 1 because we unsubscribed 
                        // jobSubs before calling runBuild the second time (see below).
                        expect(jobsStarted).toBe(2);
                        expect(jobSubsCalled).toBe(1);

                        api.disconnect();
                        done();
                    });
                }
            });
            
            // Trigger the first build. This should trigger
            // the SSE event listeners below.
            waitUntil(function() {
                return (jenkinsSessionInfo !== undefined && subscribeCount === 3);
            }, 60000).then(function() {
                runBuild();
            });
        });
    }, 60000);
});
