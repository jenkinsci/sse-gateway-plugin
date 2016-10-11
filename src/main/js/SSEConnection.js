/*
 * The MIT License
 *
 * Copyright (c) 2016, CloudBees, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

var jsModules = require('@jenkins-cd/js-modules');
var LOGGER = require('@jenkins-cd/diag').logger('sse');
var ajax = require('./ajax');
var json = require('./json');

// A map of client connection by clientId.
var clientConnections = {};

var eventSourceSupported = (window !== undefined && window.EventSource !== undefined);

/* eslint-disable no-use-before-define */

module.exports = SSEConnection;

function SSEConnection(clientId, configuration) {
    if (typeof clientId !== 'string') {
        LOGGER.error('SSE clientId not specified.');
        return;
    }

    this.clientId = clientId;
    this.configuration = extend({}, SSEConnection.DEFAULT_CONFIGURATION, configuration);

    this.jenkinsUrl = this.configuration.jenkinsUrl;
    this.eventSource = undefined;
    this.eventSourceListenerQueue = [];
    this.jenkinsSessionInfo = undefined;
    this.subscriptions = [];
    this.channelListeners = {};
    this.configurationBatchId = 0;
    this.configurationQueue = {};
    this.configurationListeners = {};
    this.nextDoConfigureTimeout = undefined;
}

SSEConnection.DEFAULT_CONFIGURATION = {
    batchConfigDelay: 100,
    sendSessionId: false
};

SSEConnection.prototype = {
    connect: function (onConnect) {
        if (this.eventSource) {
            return;
        }
        if (clientConnections[this.clientId]) {
            LOGGER.error('A connection to client having ID ' + this.clientId
                + ' already exists. You must first disconnect if you want to reconnect.');
            return;
        }

        // If the browser supports HTML5 sessionStorage, then lets append a tab specific
        // random ID to the client ID. This allows us to cleanly connect to a backend session,
        // but to do it on a per tab basis i.e. reloading from the same tab reconnects that tab
        // to the same backend dispatcher but allows each tab to have their own dispatcher,
        // avoiding weirdness when multiple tabs are open to the same "clientId".
        var tabClientId = this.clientId;
        if (window.sessionStorage) {
            var storeKey = 'jenkins-sse-gateway-client-' + this.clientId;
            tabClientId = window.sessionStorage.getItem(storeKey);

            if (!tabClientId) {
                tabClientId = this.clientId + '-' + generateTabId();
                window.sessionStorage.setItem(storeKey, tabClientId);
            }
        }

        if (!this.jenkinsUrl) {
            this.jenkinsUrl = jsModules.getRootURL();
        }
        this.jenkinsUrl = normalizeUrl(this.jenkinsUrl);

        if (!eventSourceSupported) {
            console.warn("This browser does not support EventSource. Where's the polyfill?");
            // TODO: Need to add browser poly-fills for stuff like this
            // See https://github.com/remy/polyfills/blob/master/EventSource.js
        } else {
            var connectUrl = this.jenkinsUrl + '/sse-gateway/connect?clientId='
                + encodeURIComponent(tabClientId);

            var sseConnection = this;
            ajax.get(connectUrl, function (response) {
                var listenUrl = sseConnection.jenkinsUrl + '/sse-gateway/listen/'
                    + encodeURIComponent(tabClientId);

                if (sseConnection.configuration.sendSessionId) {
                    // Sending the jsessionid helps headless clients to maintain
                    // the session with the backend.
                    var jsessionid = response.data.jsessionid;
                    listenUrl += ';jsessionid=' + jsessionid;
                }

                var EventSource = window.EventSource;
                var source = new EventSource(listenUrl);

                source.addEventListener('open', function (e) {
                    LOGGER.debug('SSE channel "open" event.', e);
                    if (e.data) {
                        sseConnection.jenkinsSessionInfo = JSON.parse(e.data);
                        if (onConnect) {
                            onConnect(sseConnection.jenkinsSessionInfo);
                        }
                    }
                }, false);
                source.addEventListener('configure', function (e) {
                    LOGGER.debug('SSE channel "configure" ACK event (see batchId on event).', e);
                    if (e.data) {
                        var configureInfo = JSON.parse(e.data);
                        notifyConfigQueueListeners(configureInfo.batchId, sseConnection);
                    }
                }, false);
                source.addEventListener('reload', function (e) {
                    LOGGER.debug('SSE channel "reload" event received. Reloading page now.', e);
                    window.location.reload(true);
                }, false);

                // Add any listeners that have been requested to be added.
                for (var i = 0; i < sseConnection.eventSourceListenerQueue.length; i++) {
                    var config = sseConnection.eventSourceListenerQueue[i];
                    source.addEventListener(config.channelName, config.listener, false);
                }

                sseConnection.eventSource = source;
            });
        }

        clientConnections[this.clientId] = this;
    },
    disconnect: function () {
        if (this.eventSource) {
            try {
                if (typeof this.eventSource.removeEventListener === 'function') {
                    for (var channelName in this.channelListeners) {
                        if (this.channelListeners.hasOwnProperty(channelName)) {
                            try {
                                this.eventSource.removeEventListener(channelName,
                                    this.channelListeners[channelName]);
                            } catch (e) {
                                LOGGER.error('Unexpected error removing listners', e);
                            }
                        }
                    }
                }
            } finally {
                try {
                    this.eventSource.close();
                } finally {
                    this.eventSource = undefined;
                    this.channelListeners = {};
                    delete clientConnections[this.clientId];
                }
            }
        }
    },
    subscribe: function () {
        clearDoConfigure(this);

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

        this.subscriptions.push({
            config: config,
            callback: callback
        });
        if (!this.configurationQueue.subscribe) {
            this.configurationQueue.subscribe = [];
        }
        this.configurationQueue.subscribe.push(config);

        if (!this.channelListeners[channelName]) {
            addChannelListener(channelName, this);
        }

        scheduleDoConfigure(this);

        if (onSubscribed) {
            addConfigQueueListener(onSubscribed, this);
        }

        return callback;
    },
    unsubscribe: function (callback, onUnsubscribed) {
        clearDoConfigure(this);

        // callback is the only mandatory param
        if (callback === undefined) {
            throw new Error('No callback provided');
        }

        var newSubscriptionList = [];
        for (var i = 0; i < this.subscriptions.length; i++) {
            var subscription = this.subscriptions[i];
            if (subscription.callback === callback) {
                if (!this.configurationQueue.unsubscribe) {
                    this.configurationQueue.unsubscribe = [];
                }
                this.configurationQueue.unsubscribe.push(subscription.config);
            } else {
                newSubscriptionList.push(subscription);
            }
        }
        this.subscriptions = newSubscriptionList;

        scheduleDoConfigure(undefined, this);

        if (onUnsubscribed) {
            addConfigQueueListener(onUnsubscribed, this);
        }
    }
};

/* eslint-disable no-param-reassign */

function resetConfigQueue(sseConnection) {
    sseConnection.configurationBatchId++;
    sseConnection.configurationQueue = {};
    sseConnection.configurationListeners[sseConnection.configurationBatchId.toString()] = [];
}

function addConfigQueueListener(listener, sseConnection) {
    // Config queue listeners are always added against the current batchId.
    // When that config batch is sent, these listeners will be notified on
    // receipt of the "configure" SSE event, which will contain that batchId.
    // See the notifyConfigQueueListeners function below.
    var batchListeners =
        sseConnection.configurationListeners[sseConnection.configurationBatchId.toString()];

    if (batchListeners) {
        batchListeners.push(listener);
    } else {
        LOGGER.error(new Error('Unexpected call to addConfigQueueListener for an ' +
            'obsolete/unknown batchId ' + sseConnection.configurationBatchId
            + '. This should never happen!!'));
    }
}

function notifyConfigQueueListeners(batchId, sseConnection) {
    var batchListeners = sseConnection.configurationListeners[batchId.toString()];
    if (batchListeners) {
        delete sseConnection.configurationListeners[batchId.toString()];
        for (var i = 0; i < batchListeners.length; i++) {
            try {
                batchListeners[i]();
            } catch (e) {
                LOGGER.error('Unexpected error calling config queue listener.', e);
            }
        }
    }
}

function clearDoConfigure(sseConnection) {
    if (sseConnection.nextDoConfigureTimeout) {
        clearTimeout(sseConnection.nextDoConfigureTimeout);
    }
    sseConnection.nextDoConfigureTimeout = undefined;
}
function scheduleDoConfigure(delay, sseConnection) {
    clearDoConfigure(sseConnection);
    var timeoutDelay = delay;
    if (timeoutDelay === undefined) {
        timeoutDelay = sseConnection.configuration.batchConfigDelay;
    }
    sseConnection.nextDoConfigureTimeout = setTimeout(function () {
        doConfigure(sseConnection);
    }, timeoutDelay);
}

function addChannelListener(channelName, sseConnection) {
    var listener = function (event) {
        if (LOGGER.isDebugEnabled()) {
            var channelEvent = JSON.parse(event.data);
            LOGGER.debug('Received event "' + channelEvent.jenkins_channel
                + '/' + channelEvent.jenkins_event + ':', channelEvent);
        }

        // Iterate through all of the subscriptions, looking for
        // subscriptions on the channel that match the filter/config.
        var processCount = 0;
        for (var i = 0; i < sseConnection.subscriptions.length; i++) {
            var subscription = sseConnection.subscriptions[i];

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
                + sseConnection.subscriptions.length + ' of). Check event ' +
                'payload against subscription ' +
                'filters - see earlier "notification configuration" request(s).');
        }
    };
    sseConnection.channelListeners[channelName] = listener;
    if (sseConnection.eventSource) {
        sseConnection.eventSource.addEventListener(channelName, listener, false);
    } else {
        sseConnection.eventSourceListenerQueue.push({
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

function doConfigure(sseConnection) {
    sseConnection.nextDoConfigureTimeout = undefined;

    var sessionInfo = sseConnection.jenkinsSessionInfo;

    if (!sessionInfo && eventSourceSupported) {
        // Can't do it yet. Need to wait for the SSE Gateway to
        // open the SSE channel + send the jenkins session info.
        scheduleDoConfigure(100, sseConnection);
    } else {
        var configureUrl = sseConnection.jenkinsUrl + '/sse-gateway/configure?batchId='
            + sseConnection.configurationBatchId;

        LOGGER.debug('Sending notification configuration request for configuration batch '
            + sseConnection.configurationBatchId + '.', sseConnection.configurationQueue);

        sseConnection.configurationQueue.dispatcherId = sessionInfo.dispatcherId;
        ajax.post(sseConnection.configurationQueue, configureUrl, sessionInfo);

        resetConfigQueue(sseConnection);
    }
}

function normalizeUrl(url) {
    if (!url) {
        return '';
    }
    // remove trailing slashes
    var newUrl = url;
    while (newUrl.charAt(newUrl.length - 1) === '/') {
        newUrl = newUrl.substring(0, newUrl.length - 1);
    }
    return newUrl;
}

/**
 * Generate a random "enough" string from the current time in
 * millis + a random generated number string.
 * @returns {string}
 */
function generateTabId() {
    return (new Date().getTime()) + '-' + (Math.random() + 1).toString(36).substring(7);
}

/**
 * Simple Object extend utility function.
 * <p/>
 * Extends the 1st argument object by mapping the following arg objects onto it.
 * @returns {object} The first argument (the target).
 */
function extend() {
    if (arguments.length < 2) {
        throw new Error('There must be at least 2 arguments.');
    }

    var target = arguments[0];
    for (var i = 1; i < arguments.length; i++) {
        var source = arguments[i];
        for (var prop in source) {
            if (source.hasOwnProperty(prop)) {
                target[prop] = source[prop];
            }
        }
    }
    return target;
}
