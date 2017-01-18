var $ = require('jquery');

$(document).ready(function start() {
    var sse = require('@jenkins-cd/sse-gateway');
    var errors = $('#errors');
    var logWindow = $('#event-logs');

    // See https://github.com/jenkinsci/sse-gateway-plugin
    var connection = sse.connect('sse-gateway-sample');

    connection.onError(function (e) {
        errors.text('Connection lost. Waiting to reconnect...');
        errors.css('display', 'block');
    });

    connection.subscribe('job', function (event) {
        var runId;
        var run;

        // Look at the browser console to see all the events
        // and their contents.
        console.log(event);

        if (event.jenkins_event === 'job_run_started') {
            runId = 'job_' + event.job_name + '_run_' + event.jenkins_object_id;
            run = $('<div class="run">');

            run.attr('id', runId);
            run.addClass('started');
            run.append($('<span class="id">').text('#' + event.jenkins_object_id));
            run.append($('<span class="time">').text(new Date().toString()));
            run.append($('<span class="jobName">').text(event.job_name));
            run.append($('<span class="eventName">').text(event.jenkins_event));

            run.attr('title', JSON.stringify(event, undefined, 4));

            logWindow.append(run);
        } else if (event.jenkins_event === 'job_run_ended') {
            runId = 'job_' + event.job_name + '_run_' + event.jenkins_object_id;
            run = $('#' + runId, logWindow);

            run.removeClass('started');
            run.addClass('ended');
            run.addClass(event.job_run_status);
            $('.eventName', run).text(event.jenkins_event);

            run.attr('title', JSON.stringify(event, undefined, 4));
        }
    });
});
