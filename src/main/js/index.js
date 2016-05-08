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
