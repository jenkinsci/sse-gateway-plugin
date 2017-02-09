
function ChannelLoadLogger(connection, channelName, triggerFunc) {
    this.connection = connection;
    this.channelName = channelName;
    this.triggerFunc = triggerFunc;
}
module.exports = ChannelLoadLogger;

ChannelLoadLogger.prototype = {
    run: function(numMessages, intervalMillis, onDone) {
        var self = this;
        var messages = new Array(numMessages);
        var runState = {
            messageCount: 0
        };
        var startTimeMillis;
        var endTimeMillis;

        var subscription = self.connection.subscribe({
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
                    if (runState.messageCount === 0) {
                        startTimeMillis = Date.now();
                    }
                    runState.messageCount++;
                    if (runState.messageCount === messages.length) {
                        try {
                            endTimeMillis = Date.now();
                            if (onDone) {
                                var failureCount = 0;
                                for (var i = 0; i < messages.length; i++) {
                                    if (typeof messages[i] === 'undefined') {
                                        console.error('We did not receive event ' + i);
                                        failureCount++;
                                    }
                                }
                                var timeTaken = endTimeMillis - startTimeMillis;
                                var timePerMessage = timeTaken/messages.length;
                                var timePerMessageMinusInterval = (timePerMessage - intervalMillis);

                                onDone({
                                    startTimeMillis: startTimeMillis,
                                    endTimeMillis: endTimeMillis,
                                    failureCount: failureCount,
                                    timeTaken: timeTaken,
                                    timePerMessage: timePerMessage,
                                    timePerMessageMinusInterval: timePerMessageMinusInterval
                                });
                            }
                        } finally {
                            self.connection.unsubscribe(subscription);
                        }
                    }
                }
            },
            onSubscribed: function() {
                self.triggerFunc(self.channelName, numMessages, intervalMillis);
            }
        });

        return runState;
    }
};