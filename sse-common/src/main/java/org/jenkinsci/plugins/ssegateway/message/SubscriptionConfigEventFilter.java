package org.jenkinsci.plugins.ssegateway.message;

import org.jenkinsci.plugins.pubsub.message.EventFilter;

/**
 * Version of {@code org.jenkinsci.plugins.pubsub.message.EventFilter} intended to maintain backwards compatibility with the SSE client.
 */
public class SubscriptionConfigEventFilter extends EventFilter {
    private static final String JENKINS_CHANNEL = "jenkins_channel";
    private static final String JENKINS_EVENT = "jenkins_event";

    @Override
    public String getChannelName() {
        return get(JENKINS_CHANNEL);
    }

    @Override
    public SubscriptionConfigEventFilter setChannelName(String name) {
        set(JENKINS_CHANNEL, name);
        return this;
    }

    @Override
    public String getEventName() {
        return get(JENKINS_EVENT);
    }

    @Override
    public SubscriptionConfigEventFilter setEventName(String name) {
        set(JENKINS_EVENT, name);
        return this;
    }

    @Override
    public SubscriptionConfigEventFilter setEventName(Enum name) {
        set(JENKINS_EVENT, name.name());
        return this;
    }


}
