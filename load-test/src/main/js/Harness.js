var ChannelLoadLogger = require('./ChannelLoadLogger');

function Harness(connection, triggerFunc, loggerFunc) {
    this.connection = connection;
    this.triggerFunc = triggerFunc;
    this.loggerFunc = loggerFunc;
}
module.exports = Harness;

Harness.prototype = {
    run: function(numMessages, intervalMillis, numChannels, onDone) {
        var self = this;
        var runStates = [];
        var runState = {
            getMessageCount: function() {
                var messageCount = 0;
                runStates.forEach(function (runState) {
                    messageCount += runState.messageCount;
                });
                return messageCount;
            }
        };
        var runResults = [];

        for (var i = 0; i < numChannels; i++) {
            var channelLogger = new ChannelLoadLogger(this.connection, 'load-test-' + i, this.triggerFunc);
            var channelState = channelLogger.run(numMessages, intervalMillis, function (result) {
                runResults.push(result);
                if (runResults.length === numChannels) {
                    // all channels are done now
                    if (onDone) {
                        var startTimeMillis = runResults[0].startTimeMillis;
                        var endTimeMillis = runResults[0].endTimeMillis;
                        var failureCount = 0;
                        for (var i = 1; i < runResults.length; i++) {
                            startTimeMillis = Math.min(startTimeMillis, runResults[i].startTimeMillis);
                            endTimeMillis = Math.max(endTimeMillis, runResults[i].endTimeMillis);
                            failureCount += runResults[i].failureCount;
                        }
                        var timeTaken = endTimeMillis - startTimeMillis;
                        var totalMessages = (numMessages * numChannels);
                        var timePerMessage = timeTaken/totalMessages;
                        var timePerMessageMinusInterval = (timePerMessage - intervalMillis);

                        // Log the results
                        if (failureCount === 0) {
                            self.loggerFunc();
                            self.loggerFunc('Yipee ... all ' + totalMessages + ' event messages received (' + numMessages + ' * ' + numChannels + ' channels) !!');
                        } else {
                            self.loggerFunc('Grrrr ... all ' + totalMessages + ' event messages NOT received (' + numMessages + ' * ' + numChannels + ' channels). Failure count ' + failureCount);
                        }
                        self.loggerFunc("Time taken: " + timeTaken + "ms, with a " + intervalMillis + "ms sleep between each message.");
                        if (numChannels === 1) {
                            self.loggerFunc("   That's " + timePerMessage + "ms per message (or " + timePerMessageMinusInterval + "ms if the sleep is factored out).");
                        } else {
                            self.loggerFunc("   That's " + timePerMessage + "ms per message.");
                        }

                        onDone();
                    }
                }
            });
            runStates.push(channelState);
        }

        return runState;
    }
};