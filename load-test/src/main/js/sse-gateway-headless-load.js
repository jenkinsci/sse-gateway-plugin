// See https://github.com/jenkinsci/sse-gateway-plugin
var sse = require('@jenkins-cd/sse-gateway/headless-client');
var jenkinsUrl = 'http://localhost:8080/jenkins';
var ChannelLoadLogger = require('./ChannelLoadLogger');

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

var triggerFunc = function(channelName, numMessages, intervalMillis) {
    var http = require('http');
    http.get(jenkinsUrl + '/sse-gateway-load/fireEvents?numMessages=' + numMessages + '&intervalMillis=' + intervalMillis);
};

var channelLogger = new ChannelLoadLogger(connection, 'load-test', triggerFunc);

channelLogger.run(numMessages, intervalMillis, function (result) {
    if (result.failureCount === 0) {
        console.log();
        console.log('Yipee ... all ' + numMessages + ' event messages received !!');
    }

    console.log();
    console.log("Time taken: " + result.timeTaken + "ms for " + numMessages + " messages, with a " + intervalMillis + "ms sleep between each message.");
    console.log("   That's " + result.timePerMessage + "ms per message (or " + result.timePerMessageMinusInterval + "ms if the sleep is factored out).");

    connection.disconnect();
});