// See https://github.com/jenkinsci/sse-gateway-plugin
var sse = require('@jenkins-cd/sse-gateway/headless-client');
var jenkinsUrl = 'http://localhost:8080/jenkins';
var Harness = require('./Harness');

var connection = sse.connect({
    clientId: 'sse-gateway-load',
    jenkinsUrl: jenkinsUrl
});

var numMessages = 20;
var intervalMillis = 20;
var numChannels = 1;

if (process.argv.length > 2) {
    numMessages = parseInt(process.argv[2]);
}
if (process.argv.length > 3) {
    intervalMillis = parseInt(process.argv[3]);
}
if (process.argv.length > 4) {
    numChannels = parseInt(process.argv[4]);
}

var triggerFunc = function(channelName, numMessages, intervalMillis) {
    var http = require('http');
    http.get(jenkinsUrl + '/sse-gateway-load/fireEvents?channelName=' + channelName + '&numMessages=' + numMessages + '&intervalMillis=' + intervalMillis);
};
var loggerFunc = function(message) {
    console.log(message?message:'');
};

var harness = new Harness(connection, triggerFunc, loggerFunc);
harness.run(numMessages, intervalMillis, numChannels, function () {
    connection.disconnect();
});