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
        loggerPre.empty();
        logWindow.append(loggerPre);
    }
    reset();

    $('#runButton').click(function () {
        var numMessages = parseInt(numMessagesInput.val());
        var intervalMillis = parseInt(intervalMillisInput.val());
        var numChannels = parseInt(numChannelsInput.val());

        var harness = new Harness(connection, triggerFunc, loggerFunc);
        harness.run(numMessages, intervalMillis, numChannels);
    });

    $('#resetButton').click(function () {
        reset();
    });
});
