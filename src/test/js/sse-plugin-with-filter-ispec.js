/*
 * Integration test.
 * <p>
 * Run from Java (via GulpRunner) with a running Jenkins (via JenkinsRule).
 */

var jsTest = require('./jsTest');
var ajax = require('../../main/js/ajax');

describe("sse plugin integration tests - with filters", function () {

    it("- test build receives events", function (done) {

        jsTest.onPage(function() {
            var sseClient = require('../../../headless-client');

            var sseConnection = sseClient.connect('sse-client-123', function(jenkinsSessionInfo) {
                
                // Once connected to the SSE Gateway, fire off a build of the sample job
                ajax.post(undefined, sseConnection.jenkinsUrl + '/job/sse-gateway-test-job/build', jenkinsSessionInfo);

                sseConnection.subscribe('job', function () {
                    expect('Should not have received this event').toBe();
                }, {
                    job_name: 'xxxxx'
                });

                sseConnection.subscribe('job', function () {
                    // Wait a sec to give time for the unexpected event to
                    // arrive (see previous subscribe to job 'xxxxx'). It 
                    // shouldn't arrive, but if it does, we throw an error. 
                    setTimeout(function() {
                        sseConnection.disconnect();
                        setTimeout(function () {
                            console.log('** done.');
                            done();
                        }, 1000);
                    }, 500);
                }, {
                    job_name: 'sse-gateway-test-job'
                });
            });
        });
    }, 60000);
});
