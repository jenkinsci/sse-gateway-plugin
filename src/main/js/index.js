var eventSourceSupported = undefined;

try {
    eventSourceSupported = (window !== undefined && window.EventSource !== undefined);
} catch (e) {}

if (eventSourceSupported) {
    var internal = require('./sse-client');
    
    internal.connect();
    
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
    function noEventSource() {
        console.warn("Jenskins SSE Gateway Client: This browser does not support EventSource. Where's the polyfill?");
        // TODO: Need to add browser poly-fills for stuff like this
        // See https://github.com/remy/polyfills/blob/master/EventSource.js
    }

    exports.subscribe = function() {
        noEventSource();
    };
    exports.unsubscribe = function() {
        noEventSource();
    };
}
