var eventSourceSupported = undefined;

try {
    eventSourceSupported = (window !== undefined && window.EventSource !== undefined);
} catch (e) {
    // No window. Probably running in a test.
}

function noEventSource() {
    console.warn('Jenskins SSE Gateway Client: This browser does not support EventSource. ' +
        "Where's the polyfill?");
    // TODO: Need to add browser poly-fills for stuff like this
    // See https://github.com/remy/polyfills/blob/master/EventSource.js
}

if (eventSourceSupported) {
    var internal = require('./sse-client');

    /**
     * Connect the SSE client to the server.
     * @param clientId The SSE client ID. This is a scrint but should be unique i.e. something
     * not likely to clash with another SSE instance in the same session.
     * @param onConnect Optionsal onConnect function.
     */
    exports.connect = internal.connect;

    /**
     * Disconnect the SSE client from the server.
     */
    exports.disconnect = internal.disconnect;

    /**
     * Subscribe to a channel.
     * @param channelName The channel name.
     * @param filter An optional filter for the channel events.
     * @param callback The callback to be called on receipt of these events.
     */
    exports.subscribe = internal.subscribe;

    /**
     * Subscribe from a channel.
     * @param callback The callback used when subscribing.
     */
    exports.unsubscribe = internal.unsubscribe;
} else {
    exports.connect = function () {
        noEventSource();
    };
    exports.disconnect = function () {
        noEventSource();
    };
    exports.subscribe = function () {
        noEventSource();
    };
    exports.unsubscribe = function () {
        noEventSource();
    };
}
