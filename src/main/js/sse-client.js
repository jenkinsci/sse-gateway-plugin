var jsModules = require('@jenkins-cd/js-modules');
var ajax = require('./ajax');
var json = require('./json');
var jenkinsUrl = jsModules.getRootURL();
var eventSource = undefined;
var jenkinsSessionInfo = undefined;
var subscriptions = [];
var channelListeners = {};
var eventSourceSupported = (window && window.EventSource);
var configurationQueue = {};
var nextDoConfigureTimeout = undefined;

if (!jenkinsUrl || jenkinsUrl.length < 1) {
    throw new Error('Invalid jenkinsUrl argument ' + jenkinsUrl);
}
if (jenkinsUrl.charAt(jenkinsUrl.length - 1) !== '/') {
    jenkinsUrl += '/';
}

exports.jenkinsUrl = jenkinsUrl;

function clearDoConfigure() {
    if (nextDoConfigureTimeout) {
        clearTimeout(nextDoConfigureTimeout);
    }
    nextDoConfigureTimeout = undefined;
}
function scheduleDoConfigure() {
    clearDoConfigure();
    nextDoConfigureTimeout = setTimeout(doConfigure, 100);
}

exports.connect = function (onConnect) {
    if (eventSource) {
        return;
    }

    if (!eventSourceSupported) {
        console.warn("This browser does not support EventSource. Where's the polyfill?");
        // TODO: Need to add browser poly-fills for stuff like this
        // See https://github.com/remy/polyfills/blob/master/EventSource.js
    } else {
        var listenUrl = jenkinsUrl + 'sse-gateway/listen';
        var EventSource = window.EventSource;
        var source = new EventSource(listenUrl);

        source.addEventListener('open', function (e) {
            if (e.data) {
                jenkinsSessionInfo = JSON.parse(e.data);
                if (onConnect) {
                    onConnect(jenkinsSessionInfo);
                }
            }
        }, false);

        eventSource = source;
    }
};

exports.disconnect = function () {
    if (eventSource) {
        eventSource.close();
        eventSource = undefined;
    }
};

exports.subscribe = function () {
    clearDoConfigure();

    var channelName;
    var filter;
    var callback;

    // sort out the args.
    for (var i = 0; i < arguments.length; i++) {
        var arg = arguments[i];
        if (typeof arg === 'string') {
            channelName = arg;
        } else if (typeof arg === 'function') {
            callback = arg;
        } else if (typeof arg === 'object') {
            filter = arg;
        }
    }

    if (channelName === undefined) {
        throw new Error('No channelName arg provided.');
    }
    if (callback === undefined) {
        throw new Error('No callback arg provided.');
    }

    var config;

    if (filter) {
        // Clone the filter as the config.
        config = JSON.parse(json.stringify(filter));
    } else {
        config = {};
    }

    config.jenkins_channel = channelName;

    subscriptions.push({
        config: config,
        callback: callback
    });
    if (!configurationQueue.subscribe) {
        configurationQueue.subscribe = [];
    }
    configurationQueue.subscribe.push(config);

    if (!channelListeners[channelName]) {
        addChannelListener(channelName);
    }

    scheduleDoConfigure();

    return callback;
};

exports.unsubscribe = function (callback) {
    clearDoConfigure();

    // callback is the only mandatory param
    if (callback === undefined) {
        throw new Error('No callback provided');
    }

    var newSubscriptionList = [];
    for (var i = 0; i < subscriptions.length; i++) {
        var subscription = subscriptions[i];
        if (subscription.callback === callback) {
            if (!configurationQueue.unsubscribe) {
                configurationQueue.unsubscribe = [];
            }
            configurationQueue.unsubscribe.push(subscription.config);
        } else {
            newSubscriptionList.push(subscription);
        }
    }
    subscriptions = newSubscriptionList;

    scheduleDoConfigure();
};

function addChannelListener(channelName) {
    var listener = function (event) {
        // Iterate through all of the subscription, looking for
        // subscriptions on the channel that match the filter/config.
        for (var i = 0; i < subscriptions.length; i++) {
            var subscription = subscriptions[i];

            if (subscription.config.jenkins_channel === channelName) {
                // Parse the data every time, in case the
                // callback modifies it.
                var parsedData = JSON.parse(event.data);
                // Make sure the data matches the config, which is the filter
                // plus the channel name (and the message should have the
                // channel name in it).
                if (containsAll(parsedData, subscription.config)) {
                    try {
                        subscription.callback(parsedData);
                    } catch (e) {
                        console.trace(e);
                    }
                }
            }
        }
    };
    channelListeners[channelName] = listener;
    eventSource.addEventListener(channelName, listener, false);
}

function containsAll(object, filter) {
    for (var property in filter) {
        if (filter.hasOwnProperty(property)) {
            var objVal = object[property];
            var filterVal = filter[property];
            if (objVal === undefined) {
                return false;
            }
            // String comparison i.e. ignore type
            if (objVal.toString() !== filterVal.toString()) {
                return false;
            }
        }
    }
    return true;
}

function doConfigure() {
    nextDoConfigureTimeout = undefined;

    if (!jenkinsSessionInfo && eventSourceSupported) {
        // Can't do it yet. Need to wait for the SSE Gateway to
        // open the SSE channel + send the jenkins session info.
        scheduleDoConfigure();
    } else {
        var configureUrl = jenkinsUrl + 'sse-gateway/configure';

        configurationQueue.dispatcher = jenkinsSessionInfo.dispatcher;
        ajax.post(configurationQueue, configureUrl, jenkinsSessionInfo);

        // reset the queue
        configurationQueue = {};
    }
}
