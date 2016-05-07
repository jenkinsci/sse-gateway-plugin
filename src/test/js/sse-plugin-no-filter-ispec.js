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
                // Trigger the first build. This should trigger
                // the SSE event listeners below.
                runBuild();
            });

            // Listen for sse events and use them to determine
            // if subscribe/unsubscribe is working properly.
            var sseEvents = [];
            api.subscribe('sse', function (data) {
                sseEvents.push(data);
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

                    // wait 60s for jobsStarted to equal 2
                    waitUntil(function () {
                        return jobsStarted === 2;
                    }, 60000).then(function() {
                        // The check everything, especially that the unsubscribe worked
                        // i.e. that this callback was not called again after.
                        try {
                            // 2 subscribe events and 1 unsubscribe
                            expect(sseEvents.length).toBe(3);

                            // Check the last 2 events
                            expect(sseEvents[1].jenkins_event).toBe('subscribe');
                            expect(sseEvents[1].sse_numsubs).toBe('2');
                            expect(sseEvents[2].jenkins_event).toBe('unsubscribe');
                            expect(sseEvents[2].sse_numsubs).toBe('1');

                            // jobSubsCalled should only be 1 because we unsubscribed 
                            // jobSubs before calling runBuild the second time (see below).
                            expect(jobsStarted).toBe(2);
                            expect(jobSubsCalled).toBe(1);
                        } finally {
                            api.disconnect();
                            done();
                        }
                    });

                    // Run the build the second time
                    runBuild();
                }
            });
        });
    }, 60000);
});
