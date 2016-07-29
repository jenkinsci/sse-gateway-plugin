/**
 * A Utility for exposing the SSE Client for non browser environments e.g. when used
 * in a test environment for tracking build progress.
 */

// Need to provide some things that are available in browser envs but not
// in a non-browser env.
if (!global.XMLHttpRequest) {
    global.XMLHttpRequest = require("xmlhttprequest").XMLHttpRequest;
}
if (global.window === undefined) {
    global.window = {};
}
if (!global.window.EventSource) {
    global.window.EventSource = require('eventsource');
}

var client = require('./src/main/js/sse-client');

client.configure({
    batchConfigDelay: 0, // exec batch configs immediately i.e. no delay for batching up.
    sendSessionId: true  // maintain sessions with the backend via jsessionid
});

module.exports = client;