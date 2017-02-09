// See https://github.com/jenkinsci/sse-gateway-plugin
var sse = require('@jenkins-cd/sse-gateway/headless-client');
var jenkinsUrl = 'http://localhost:8080/jenkins';

var connection = sse.connect({
    clientId: 'sse-gateway-load',
    jenkinsUrl: jenkinsUrl
});

var numMessages = 20;
var intervalMillis = 20;

if (process.argv.length > 2) {
    numMessages = parseInt(process.argv[2]);
}
if (process.argv.length > 3) {
    intervalMillis = parseInt(process.argv[3]);
}

var messages = new Array(numMessages);
var messageCount = 0;
var startTimeMillis;
var endTimeMillis;

var subscription = connection.subscribe({
    channelName: 'load-test',
    onEvent: function (event) {
        try {
            var eventId = parseInt(event.eventId);
            if (typeof messages[eventId] === 'undefined') {
                messages[eventId] = true;
            } else {
                console.error('We already received event ' + eventId);
            }
        } finally {
            if (messageCount === 0) {
                startTimeMillis = Date.now();
            }
            messageCount++;
            if (messageCount === messages.length) {
                endTimeMillis = Date.now();
                try {
                    checkMessagesReceived();
                } finally {
                    shutdown();
                }
            }
        }
    },
    onSubscribed: function() {
        var http = require('http');
        http.get(jenkinsUrl + '/sse-gateway-load/fireEvents?numMessages=' + numMessages + '&intervalMillis=' + intervalMillis);
    }
});

function checkMessagesReceived() {
    var failureCount = 0;
    for (var i = 0; i < messages.length; i++) {
        if (typeof messages[i] === 'undefined') {
            console.error('We did not receive event ' + i);
            failureCount++;
        }
    }
    if (failureCount === 0) {
        console.log();
        console.log('Yipee ... all ' + messages.length + ' event messages received !!');
    }

    if (startTimeMillis && endTimeMillis) {
        var timeTaken = endTimeMillis - startTimeMillis;
        var timePerMessage = timeTaken/messages.length;
        var timePerMessageMinusInterval = (timePerMessage - intervalMillis);

        console.log();
        console.log("Time taken: " + timeTaken + "ms for " + messages.length + " messages, with a " + intervalMillis + "ms sleep between each message.");
        console.log("   That's " + timePerMessage + "ms per message (or " + timePerMessageMinusInterval + "ms if the sleep is factored out).");
    }
}

function shutdown() {
    connection.unsubscribe(subscription);
    connection.disconnect();
}