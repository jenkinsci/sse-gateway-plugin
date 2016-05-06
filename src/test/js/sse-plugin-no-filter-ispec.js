/*
 * Integration test.
 * <p>
 * Run from Java (via GulpRunner) with a running Jenkins (via JenkinsRule).
 */

var jsTest = require('@jenkins-cd/js-test');

global.XMLHttpRequest = require("xmlhttprequest").XMLHttpRequest;

describe("sse plugin integration tests - no filters", function () {

    it("- test build receives events", function (done) {
        jsTest.onPage(function() {
            window.EventSource = require('eventsource');

            var api = jsTest.requireSrcModule('sse-client');

            api.connect(function(jenkinsSessionInfo) {
                var ajax = jsTest.requireSrcModule('ajax');
                
                ajax.post(undefined, api.jenkinsUrl + 'job/sse-gateway-test-job/build', jenkinsSessionInfo);
            });

            var sseEvents = [];
            api.subscribe('sse', function (data) {
                sseEvents.push(data);
            });
            
            var jobSubs = api.subscribe('job', function () {
                api.unsubscribe(jobSubs);
                
                setTimeout(function() {
                    try {
                        // 2 subscribe events and 1 unsubscribe
                        expect(sseEvents.length).toBe(3);

                        // Check the last 2 events
                        expect(sseEvents[1].jenkins_event).toBe('subscribe');
                        expect(sseEvents[1].sse_numsubs).toBe('2');
                        expect(sseEvents[2].jenkins_event).toBe('unsubscribe');
                        expect(sseEvents[2].sse_numsubs).toBe('1');
                    } finally {
                        api.disconnect();
                        done();
                    }
                }, 500);
            });
        });
    }, 300000);
});
