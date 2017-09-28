package org.jenkinsci.plugins.ssegateway.sse;

import javax.servlet.http.HttpSessionEvent;
import javax.servlet.http.HttpSessionListener;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

public class SSEHttpSessionListener implements HttpSessionListener {
    private static final Logger LOGGER = Logger.getLogger(SSEHttpSessionListener.class.getName());

    @Override
    public void sessionCreated(final HttpSessionEvent se) {
        // no-op
    }

    @Override
    public void sessionDestroyed(HttpSessionEvent httpSessionEvent) {
        try {
            Map<String, EventDispatcher> dispatchers = EventDispatcherFactory.getDispatchers(httpSessionEvent.getSession());
            try {
                for (EventDispatcher dispatcher : dispatchers.values()) {
                    try {
                        dispatcher.unsubscribeAll();
                    } catch (Exception e) {
                        LOGGER.log(Level.FINE, "Error during unsubscribeAll() for dispatcher " + dispatcher.getId() + ".", e);
                    }
                }
            } finally {
                dispatchers.clear();
            }
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "Error during session cleanup. The session has probably timed out.", e);
        }
    }
}
