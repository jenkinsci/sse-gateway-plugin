var $ = require('jquery');

$(document).ready(function start() {
    // See https://github.com/jenkinsci/sse-gateway-plugin
    var sse = require('@jenkins-cd/sse-gateway');
    var connection = sse.connect('sse-gateway-load');
    var Harness = require('./Harness');

    var numMessagesInput = $('#numMessages');
    var intervalMillisInput = $('#intervalMillis');
    var numChannelsInput = $('#numChannels');
    var logWindow = $('#event-logs');
    var messageCounter = $('<div class="messageCounter">');
    var loggerPre = $('<pre>');

    var triggerFunc = function(channelName, numMessages, intervalMillis) {
        $.get('./fireEvents', {
            channelName: channelName,
            numMessages: numMessages,
            intervalMillis: intervalMillis
        });
    };
    var loggerFunc = function(message) {
        loggerPre.append((message?message:'') + '\n');
    };
    function reset() {
        logWindow.empty();
        messageCounter.empty();
        loggerPre.empty();
        logWindow.append(messageCounter);
        logWindow.append(loggerPre);
    }
    reset();

    $('#runButton').click(function () {
        var numMessages = parseInt(numMessagesInput.val());
        var intervalMillis = parseInt(intervalMillisInput.val());
        var numChannels = parseInt(numChannelsInput.val());
        var done = false;

        var harness = new Harness(connection, triggerFunc, loggerFunc);
        var runState = harness.run(numMessages, intervalMillis, numChannels, function() {
            done = true;
        });
        function trackMessages() {
            messageCounter.text(runState.getMessageCount());
            if (!done) {
                setTimeout(trackMessages, 1000);
            }
        }
        trackMessages();
    });

    $('#resetButton').click(function () {
        reset();
    });
});
