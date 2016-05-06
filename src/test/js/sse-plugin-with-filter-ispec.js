/*
 * Integration test.
 * <p>
 * Run from Java (via GulpRunner) with a running Jenkins (via JenkinsRule).
 */

var jsTest = require('@jenkins-cd/js-test');

global.XMLHttpRequest = require("xmlhttprequest").XMLHttpRequest;

describe("sse plugin integration tests - with filters", function () {

    it("- test build receives events", function (done) {
        jsTest.onPage(function() {
            window.EventSource = require('eventsource');

            var api = jsTest.requireSrcModule('sse-client');

            api.connect(function(jenkinsSessionInfo) {
                var ajax = jsTest.requireSrcModule('ajax');
                
                // Once connected to the SSE Gateway, fire off a build of the sample job
                ajax.post(undefined, api.jenkinsUrl + 'job/sse-gateway-test-job/build', jenkinsSessionInfo);
            });

            api.subscribe('job', function () {
                expect('Should not have received this event').toBe();
            }, {
                job_name: 'xxxxx'
            });

            api.subscribe('job', function () {
                // Wait a sec to give time for the unexpected event to arrive.
                // It shouldn't arrive, but if it does, we throw an error. 
                setTimeout(function() {
                    api.disconnect();
                    done();
                }, 500);
            }, {
                job_name: 'sse-gateway-test-job'
            });
        });
    }, 300000);
});
