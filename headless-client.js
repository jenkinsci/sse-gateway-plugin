/**
 * A Utility for exposing the SSE Client for non browser environments e.g. when used
 * in a test environment for tracking build progress.
 */

// Need to provide some things that are available in browser envs but not
// in a non-browser env.
global.XMLHttpRequest = require("xmlhttprequest").XMLHttpRequest;
if (global.window === undefined) {
    global.window = {};
}
global.window.EventSource = require('eventsource');

var client = require('./src/main/js/sse-client');

// exec configs immediately i.e. no delay for batching up.
client.DEFAULT_BATCH_CONFIG_DELAY = 0;

module.exports = client;