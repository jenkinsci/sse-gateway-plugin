var jsModules = require('@jenkins-cd/js-modules');
var LOGGER = require('@jenkins-cd/diag').logger('sse');
var ajax = require('./ajax');
var json = require('./json');
var jenkinsUrl = undefined;
var eventSource = undefined;
var eventSourceListenerQueue = [];
var jenkinsSessionInfo = undefined;
var subscriptions = [];
var channelListeners = {};
var eventSourceSupported = (window !== undefined && window.EventSource !== undefined);
var configurationBatchId = 0;
var configurationQueue = {};
var configurationListeners = {};
var nextDoConfigureTimeout = undefined;

exports.DEFAULT_BATCH_CONFIG_DELAY = 100;

function resetConfigQueue() {
    configurationBatchId++;
    configurationQueue = {};
    configurationListeners[configurationBatchId.toString()] = [];
}
resetConfigQueue();

function addConfigQueueListener(listener) {
    // Config queue listeners are always added against the current batchId.
    // When that config batch is sent, these listeners will be notified on
    // receipt of the "configure" SSE event, which will contain that batchId.
    // See the notifyConfigQueueListeners function below.
    var batchListeners = configurationListeners[configurationBatchId.toString()];
    if (batchListeners) {
        batchListeners.push(listener);
    } else {
        LOGGER.error(new Error('Unexpected call to addConfigQueueListener for an ' +
            'obsolete/unknown batchId ' + configurationBatchId + '. This should never happen!!'));
    }
}

function notifyConfigQueueListeners(batchId) {
    var batchListeners = configurationListeners[batchId.toString()];
    if (batchListeners) {
        delete configurationListeners[batchId.toString()];
        for (var i = 0; i < batchListeners.length; i++) {
            try {
                batchListeners[i]();
            } catch (e) {
                LOGGER.error('Unexpected error calling config queue listener.', e);
            }
        }
    }
}

function clearDoConfigure() {
    if (nextDoConfigureTimeout) {
        clearTimeout(nextDoConfigureTimeout);
    }
    nextDoConfigureTimeout = undefined;
}
function scheduleDoConfigure(delay) {
    clearDoConfigure();
    var timeoutDelay = delay;
    if (timeoutDelay === undefined) {
        timeoutDelay = exports.DEFAULT_BATCH_CONFIG_DELAY;
    }
    nextDoConfigureTimeout = setTimeout(doConfigure, timeoutDelay);
}
function discoverJenkinsUrl() {
    jenkinsUrl = jsModules.getRootURL();
    if (!jenkinsUrl) {
        jenkinsUrl = '';
    } else {
        // only in tests, this is suffixed ...
        while (jenkinsUrl.charAt(jenkinsUrl.length - 1) === '/') {
            jenkinsUrl = jenkinsUrl.substring(0, jenkinsUrl.length - 1);
        }
    }
}

exports.connect = function (clientId, onConnect) {
    if (eventSource) {
        return;
    }

    jenkinsUrl = undefined;
    if (arguments.length === 1 && typeof arguments[0] === 'object') {
        var configObj = arguments[0];
        /* eslint-disable */
        clientId = configObj.clientId;
        onConnect = configObj.onConnect;
        jenkinsUrl = configObj.jenkinsUrl;
        /* eslint-enable */
    }

    if (!jenkinsUrl) {
        discoverJenkinsUrl();
    }
    exports.jenkinsUrl = jenkinsUrl;

    if (typeof clientId !== 'string') {
        LOGGER.error("SSE clientId not specified in 'connect' request.");
        return;
    }

    if (!eventSourceSupported) {
        console.warn("This browser does not support EventSource. Where's the polyfill?");
        // TODO: Need to add browser poly-fills for stuff like this
        // See https://github.com/remy/polyfills/blob/master/EventSource.js
    } else {
        var connectUrl = jenkinsUrl + '/sse-gateway/connect?clientId='
                                    + encodeURIComponent(clientId);
        ajax.get(connectUrl, function () {
            var listenUrl = jenkinsUrl + '/sse-gateway/listen/' + encodeURIComponent(clientId);
            var EventSource = window.EventSource;
            var source = new EventSource(listenUrl);

            source.addEventListener('open', function (e) {
                LOGGER.debug('SSE channel "open" event.', e);
                if (e.data) {
                    jenkinsSessionInfo = JSON.parse(e.data);
                    if (onConnect) {
                        onConnect(jenkinsSessionInfo);
                    }
                }
            }, false);
            source.addEventListener('configure', function (e) {
                LOGGER.debug('SSE channel "configure" ACK event (see batchId on event).', e);
                if (e.data) {
                    var configureInfo = JSON.parse(e.data);
                    notifyConfigQueueListeners(configureInfo.batchId);
                }
            }, false);

            // Add any listeners that have been requested to be added.
            for (var i = 0; i < eventSourceListenerQueue.length; i++) {
                var config = eventSourceListenerQueue[i];
                source.addEventListener(config.channelName, config.listener, false);
            }

            eventSource = source;
        });
    }
};

exports.disconnect = function () {
    if (eventSource) {
        try {
            if (typeof eventSource.removeEventListener === 'function') {
                for (var channelName in channelListeners) {
                    if (channelListeners.hasOwnProperty(channelName)) {
                        try {
                            eventSource.removeEventListener(channelName,
                                channelListeners[channelName]);
                        } catch (e) {
                            LOGGER.error('Unexpected error removing listners', e);
                        }
                    }
                }
            }
        } finally {
            try {
                eventSource.close();
            } finally {
                eventSource = undefined;
                channelListeners = {};
            }
        }
    }
};

exports.subscribe = function () {
    clearDoConfigure();

    var channelName;
    var filter;
    var callback;
    var onSubscribed;

    // sort out the args.
    if (arguments.length === 1 && typeof arguments[0] === 'object') {
        var configObj = arguments[0];
        channelName = configObj.channelName;
        callback = configObj.onEvent;
        filter = configObj.filter;
        onSubscribed = configObj.onSubscribed;
    } else {
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

    if (onSubscribed) {
        addConfigQueueListener(onSubscribed);
    }

    return callback;
};

exports.unsubscribe = function (callback, onUnsubscribed) {
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

    if (onUnsubscribed) {
        addConfigQueueListener(onUnsubscribed);
    }
};

function addChannelListener(channelName) {
    var listener = function (event) {
        if (LOGGER.isDebugEnabled()) {
            var channelEvent = JSON.parse(event.data);
            LOGGER.debug('Received event "' + channelEvent.jenkins_channel
                + '/' + channelEvent.jenkins_event + ':', channelEvent);
        }

        // Iterate through all of the subscriptions, looking for
        // subscriptions on the channel that match the filter/config.
        var processCount = 0;
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
                        processCount++;
                        subscription.callback(parsedData);
                    } catch (e) {
                        console.trace(e);
                    }
                }
            }
        }
        if (processCount === 0) {
            LOGGER.debug('Event not processed by any active listeners ('
                + subscriptions.length + ' of). Check event payload against subscription ' +
                'filters - see earlier "notification configuration" request(s).');
        }
    };
    channelListeners[channelName] = listener;
    if (eventSource) {
        eventSource.addEventListener(channelName, listener, false);
    } else {
        eventSourceListenerQueue.push({
            channelName: channelName,
            listener: listener
        });
    }
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
        scheduleDoConfigure(100);
    } else {
        var configureUrl = jenkinsUrl + '/sse-gateway/configure?batchId=' + configurationBatchId;

        LOGGER.debug('Sending notification configuration request for configuration batch '
            + configurationBatchId + '.', configurationQueue);

        configurationQueue.dispatcherId = jenkinsSessionInfo.dispatcherId;
        ajax.post(configurationQueue, configureUrl, jenkinsSessionInfo);

        resetConfigQueue();
    }
}
