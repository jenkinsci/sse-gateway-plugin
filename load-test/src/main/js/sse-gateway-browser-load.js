var $ = require('jquery');

$(document).ready(function start() {
    // See https://github.com/jenkinsci/sse-gateway-plugin
    var sse = require('@jenkins-cd/sse-gateway');
    var connection = sse.connect('sse-gateway-load');

    var numMessagesInput = $('#numMessages');
    var intervalMillisInput = $('#intervalMillis');
    var numMessages;
    var intervalMillis;
    var logWindow = $('#event-logs');
    var messageReceivedCount = 0;
    var startTimeMillis;
    var endTimeMillis;

    $('#runButton').click(function () {
        numMessages = parseInt(numMessagesInput.val());
        intervalMillis = parseInt(intervalMillisInput.val());

        if ($('.event-log', logWindow).length === 0) {
            logWindow.empty();
            for (var i = 0; i < numMessages; i++) {
                var logEvent = $('<div class="event-log">');
                logEvent.attr('id', 'event-log-' + i);
                logEvent.text(i);
                logWindow.append(logEvent);
            }

            messageReceivedCount = 0;
            $.get('./fireEvents', {
                numMessages: numMessages,
                intervalMillis: intervalMillisInput.val()
            });
        }
    });
    $('#resetButton').click(function () {
        logWindow.empty();
    });

    connection.subscribe('load-test', function (event) {
        $('#event-log-' + event.eventId).remove();

        if (messageReceivedCount === 0) {
            startTimeMillis = Date.now();
        }
        messageReceivedCount++;
        if (messageReceivedCount === numMessages) {
            endTimeMillis = Date.now();
            if ($('.event-log', logWindow).length === 0) {
                var timeTaken = endTimeMillis - startTimeMillis;
                var timePerMessage = timeTaken/numMessages;
                var timePerMessageMinusInterval = (timePerMessage - intervalMillis);

                logWindow.append("<div>Time taken: " + timeTaken + "ms for " + numMessages + " messages, with a " + intervalMillis + "ms sleep between each message.</div>");
                logWindow.append("<div>That's " + timePerMessage + "ms per message (or " + timePerMessageMinusInterval + "ms if the sleep is factored out).</div>");
                logWindow.append("<p/>");
                logWindow.append("<div>Also remember that some of the overall time may (may not - just keep it in mind) relate to client side DOM manipulation (looking for and removing the little message indicators). The CLI node load test may be a more accurate timing test.</div>");
            }
        }
    });
});
