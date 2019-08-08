# [Server Sent Events (SSE)](https://html.spec.whatwg.org/multipage/comms.html#server-sent-events)

Gateway plugin for Jenkins.
Uses the [pubsub-light-plugin] jenkins-module to receive light-weight events and forward them into browser-land via SSE.

[![Build Status](https://ci.jenkins.io/job/Plugins/job/sse-gateway-plugin/job/master/badge/icon)](https://ci.jenkins.io/job/Plugins/job/sse-gateway-plugin/job/master/)
[![Jenkins Plugin](https://img.shields.io/jenkins/plugin/v/sse-gateway.svg)](https://plugins.jenkins.io/sse-gateway)
[![Jenkins Plugin Installs](https://img.shields.io/jenkins/plugin/i/sse-gateway.svg?color=blue)](https://plugins.jenkins.io/sse-gateway)

# Install

```sh
npm install --save @jenkins-cd/sse-gateway
```

# Requirements

This plugin requires Jenkins version 2.60.3 or later.

2.60.3+ is needed because it supports Servlet 3 asynchronous requests, which are needed for Server Sent Events and its the first version of Jenkins that requires java 8.

# Usage

The API is quite simple, allowing you to `subscribe` to (and `unsubscribe` from) Jenkins event
notification "channels".

## Configuration

Due to some possible memory leak if message are never delivered, the messages have some System properties configuration parameters to avoid such issue:
* `org.jenkinsci.plugins.ssegateway.sse.EventDispatcher.RETRY_QUEUE_EVENT_LIFETIME` (default 300sec): definite how long an entry can stay in the queue
* `org.jenkinsci.plugins.ssegateway.sse.EventDispatcher.RETRY_QUEUE_PROCESSING_DELAY` (default 250ms): time between each send retry


## Subscribing to "job" channel events (basic)

The "job" channel is where you listen for events relating to Jenkins Jobs, all of which are enumerated in
 [the Events.JobChannel Javadoc](http://jenkinsci.github.io/pubsub-light-plugin/org/jenkinsci/plugins/pubsub/Events.JobChannel.html).

```javascript
var sse = require('@jenkins-cd/sse-gateway');

// Connect to the SSE Gateway, providing an optional client Id.
var connection = sse.connect('myplugin');

// subscribe to all events on the "job" channel...
var jobSubs = connection.subscribe('job', function (event) {
    var event = event.jenkins_event;
    var jobName = event.job_name;

    if (event === 'job_run_ended') {
        var runStatus = event.job_run_status;
        var runUrl = event.jenkins_object_url;
        
        // Do whatever ....
    }    
});


// And some time later, unsubscribe using the return from the subscribe...
connection.unsubscribe(jobSubs);
```

## Subscribing to "job" channel events (with a filter)

The above example subscribes to all events on the "job" channel i.e. all events for all jobs in the 
Jenkins instance. This may be what you want in some cases, but in many cases you are just interested in
 receiving specific events. To do this, you simply need to specify a "filter" when subscribing
 to the channel.
 
 For example, to only receive "FAILURE" events for the "order-management-webapp-deploy" job:
 
```javascript
var sse = require('@jenkins-cd/sse-gateway');

// Connect to the SSE Gateway, providing an optional client Id.
var connection = sse.connect('myplugin');

// Add a filter as the last parameter ...
var jobSubs = connection.subscribe('job', function (event) {
    // this event is only relating to 'order-management-webapp-deploy' ...
}, {
    job_name: 'order-management-webapp-deploy',
    job_run_status: 'FAILURE'
});

// And some time later, unsubscribe using the return from the subscribe...
connection.unsubscribe(jobSubs);
```

## Handling connection errors

As is to be expected, the connection to Jenkins can be lost. To handle this situation, simply register an `onError` handler with the connection instance.

```javascript
var sse = require('@jenkins-cd/sse-gateway');

// Connect to the SSE Gateway.
var connection = sse.connect('myplugin');

// Connection error handling...
connection.onError(function (e) {
    // Check the connection...
    connection.waitConnectionOk(function(status) {
        if (status.connectError) {
            // The last attempt to connect was a failure, so
            // notify the user in some way....
            
        } else if (status.connectErrorCount > 0) {
            // The last attempt to connect was not a failure,
            // but we had earlier failures, so undo
            // earlier error notifications etc ...
            
            // And perhaps reload the current page, forcing
            // a login if needed....
            setTimeout(function() {
                window.location.reload(true);
            }, 2000);
        }
    });
});

// etc...
```

> Note that only one handler can be registered per `connection` instance.

Note how the supplied `connection.onError` handler makes a call to `connection.waitConnectionOk`.
  `connection.waitConnectionOk` takes a connection status callback handler. This handler is called
   periodically until the connection is ok again i.e. it can be called more than once, constantly getting
   feedback on the connection state.

# Internet Explorer Support

As always with Internet Explorer, there are issues. It doesn't support the SSE `EventSource` so in order to
use it on Internet Explorer, please make sure that a polyfill is added to the page before your app. We have
used [this one](https://github.com/remy/polyfills/blob/master/EventSource.js) and found it to work fine.

To add this polyfill to your `.jelly` file, simply include the following adjunct as early as possible.

```html
<st:adjunct includes="org.jenkinsci.plugins.ssegateway.sse.EventSource" />
```

# SSE Events in headless JavaScript environments

To use this API in a headless/non-browser JavaScript environment (e.g. server-side JavaScript, or a test environment), just
`require` the `headless-client` e.g.:

```javascript
var sse = require('@jenkins-cd/sse-gateway/headless-client');

// etc....
```

# Browser Diagnostics

The SSE Gateway client code uses the `@jenkins-cd/logging` package for client-side/browser logging. See the [Browser Configuration](https://tfennelly.github.io/jenkins-js-logging/index.html#browser-config) docs for how to configure logging in your browser, configuring the stored value of `jenkins-instance/logging/categories:org.jenkinsci.sse` for SSE logs. 

# Sample Plugin

See the [sse-gateway-sample-plugin](https://github.com/tfennelly/sse-gateway-sample-plugin).

[pubsub-light-plugin]: https://github.com/jenkinsci/pubsub-light-plugin
