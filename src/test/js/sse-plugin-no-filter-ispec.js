/*
 * Integration test.
 * <p>
 * Run from Java (via GulpRunner) with a running Jenkins (via JenkinsRule).
 */

var jsTest = require('./jsTest');
var waitUntil = require('wait-until-promise').default;
var ajax = require('../../main/js/ajax');

describe("sse plugin integration tests - subscribe and unsubscribe - no filters", function () {

    it("- test build receives events", function (done) {
        jsTest.onPage(function() {
            var sseClient = require('../../../headless-client');
            var jenkinsSessionInfo;

            var sseConnection = sseClient.connect('sse-client-123', function(jenkinsSession) {
                jenkinsSessionInfo = jenkinsSession;

                // Listen for sse events and use them to determine
                // if subscribe/unsubscribe is working properly.
                var sseEvents = [];
                var subscribeCount = 0;
                var unsubscribeCount = 0;
                var onSubscibedCalled = false;
                var onUnsubscibedCalled = false;

                function runBuild() {
                    ajax.post(undefined, sseConnection.jenkinsUrl + '/job/sse-gateway-test-job/build', jenkinsSessionInfo);
                }

                sseConnection.subscribe({
                    channelName: 'sse',
                    onEvent: function (event) {
                        sseEvents.push(event);
                        if (event.jenkins_event === 'subscribe') {
                            subscribeCount++;
                        } else if (event.jenkins_event === 'unsubscribe') {
                            unsubscribeCount++;
                        }
                    },
                    onSubscribed: function() {
                        onSubscibedCalled = true;
                    }
                });
                
                // jobsStarted should eventually equal number of jobs started i.e. 2
                var jobsStarted = 0;
                sseConnection.subscribe('job', function (event) {
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
                var jobSubs = sseConnection.subscribe('job', function (event) {
                    if (event.jenkins_event === 'job_run_started') {
                        // Test unsubscribe now by unsubscribing jobSubs.
                        // See the waitUntil below.
                        sseConnection.unsubscribe(jobSubs, function() {
                            onUnsubscibedCalled = true;
                        });
    
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
                            expect(onSubscibedCalled).toBe(true);
                            expect(unsubscribeCount).toBe(1);
                            expect(onUnsubscibedCalled).toBe(true);
    
                            // jobSubsCalled should only be 1 because we unsubscribed 
                            // jobSubs before calling runBuild the second time (see below).
                            expect(jobsStarted).toBe(2);
                            expect(jobSubsCalled).toBe(1);

                            sseConnection.disconnect();
                            // give jenkins some time to send remaining events
                            // e.g. 'job_run_queue_task_complete'
                            setTimeout(function () {
                                console.log('** done.');
                                done();
                            }, 1000);
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
        });
    }, 60000);
});
