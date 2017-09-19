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

// Modify the default connection config for the headless client.
var SSEConnection = require('./src/main/js/SSEConnection');
SSEConnection.DEFAULT_CONFIGURATION.batchConfigDelay = 0; // exec batch configs immediately i.e. no delay for batching up.
SSEConnection.DEFAULT_CONFIGURATION.sendSessionId = true; // maintain sessions with the backend via jsessionid

module.exports = require('./src/main/js/sse-client');