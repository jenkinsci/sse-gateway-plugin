var $ = require('jquery');

$(document).ready(function start() {
    // See https://github.com/jenkinsci/sse-gateway-plugin
    var sse = require('@jenkins-cd/sse-gateway');
    var connection = sse.connect('sse-gateway-load');

    var numMessagesInput = $('#numMessages');
    var intervalMillisInput = $('#intervalMillis');
    var logWindow = $('#event-logs');

    $('#runButton').click(function () {
        if ($('.event-log', logWindow).length === 0) {
            var numMessages = parseInt(numMessagesInput.val());

            for (var i = 0; i < numMessages; i++) {
                var logEvent = $('<div class="event-log">');
                logEvent.attr('id', 'event-log-' + i);
                logEvent.text(i);
                logWindow.append(logEvent);
            }

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
    });
});
