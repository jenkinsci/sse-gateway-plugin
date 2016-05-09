[Server Sent Events (SSE)](https://html.spec.whatwg.org/multipage/comms.html#server-sent-events) Gateway plugin for Jenkins.

Uses the [jenkins-pubsub-light-module] jenkins-module to receive light-weight events and forward them into browser-land via SSE.

# JavaScript API

The API is quite simple, allowing you to `subscribe` to (and `unsubscribe` from) Jenkins event
notification "channels".

## Subscribing to "job" channel events (basic)

The "job" channel is where you listen for events relating to Jenkins Jobs, all of which are enumerated in
 [the Events.JobChannel Javadoc](http://tfennelly.github.io/jenkins-pubsub-light-module/org/jenkins/pubsub/Events.JobChannel.html).

```javascript
var sse = require('@jenkins-cd/sse-gateway');

// subscribe to all events on the "job" channel...
var jobSubs = sse.subscribe('job', function (event) {
    var event = event.jenkins_event;
    var jobName = event.job_name;

    if (event === 'job_run_ended') {
        var runStatus = event.job_run_status;
        var runUrl = event.jenkins_object_url;
        
        // Do whatever ....
    }    
});


// And some time later, unsubscribe using the return from the subscribe...
sse.unsubscribe(jobSubs);
```

## Subscribing to "job" channel events (with a filter)

The above example subscribes to all events on the "job" channel i.e. all events for all jobs in the 
Jenkins instance. This may be what you want in some cases, but in many cases you are just interested in
 receiving specific events. To do this, you simply need to specify a "filter" when subscribing
 to the channel. For example, to only receive events for the "order-management-webapp-deploy" job:
 
```javascript
var sse = require('@jenkins-cd/sse-gateway');

// Add a filter as the last parameter ...
var jobSubs = sse.subscribe('job', function (event) {
    // this event is only relating to 'order-management-webapp-deploy' ...
}, {
    job_name: 'order-management-webapp-deploy'
});

// And some time later, unsubscribe using the return from the subscribe...
sse.unsubscribe(jobSubs);
```

[jenkins-pubsub-light-module]: https://github.com/tfennelly/jenkins-pubsub-light-module