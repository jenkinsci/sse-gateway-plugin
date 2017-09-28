package org.jenkinsci.plugins.ssegateway;

import org.acegisecurity.Authentication;
import org.jenkinsci.plugins.ssegateway.sse.EventDispatcher;
import org.jenkinsci.plugins.ssegateway.sse.EventDispatcherFactory;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * SSE base functionality for web applications based on Servlet API.
 */
public class SseServletBase {
    private static final Logger LOGGER = Logger.getLogger(SseServletBase.class.getName());

    /**
     * Re-initialize or create a {@link EventDispatcher} for the clientId associated with this request.
     *
     * @throws IOException
     */
    public void initDispatcher(HttpServletRequest request, HttpServletResponse response, Authentication authentication) throws IOException {
        String clientId = request.getParameter("clientId");

        if (clientId == null) {
            throw new IOException("No 'clientId' parameter specified in connect request.");
        }

        HttpSession session = request.getSession();
        EventDispatcher dispatcher = EventDispatcherFactory.getDispatcher(clientId, session);

        // If there was already a dispatcher with this ID, then remove
        // all subscriptions from it and reuse the instance.
        if (dispatcher != null) {
            LOGGER.log(Level.FINE, "We already have a Dispatcher for clientId {0}. Removing all subscriptions on the existing Dispatcher instance and reusing it.", dispatcher.toString());
            dispatcher.unsubscribeAll();
        } else {
            // Else create a new instance with this id.
            EventDispatcherFactory.newDispatcher(clientId, session, authentication);
        }

        response.setStatus(HttpServletResponse.SC_OK);
    }

    /**
     * Returns true if the given {@link SubscriptionConfigQueue.SubscriptionConfig} is ready to be enqueued.
     *
     * @throws IOException
     */
    public boolean validateSubscriptionConfig(SubscriptionConfigQueue.SubscriptionConfig subscriptionConfig) throws IOException {
        LOGGER.log(Level.FINE, "Processing configuration request. batchId={0}", subscriptionConfig.getBatchId());
        return subscriptionConfig.getDispatcherId() != null && subscriptionConfig.hasConfigs();
    }

    /**
     * Returns true if the given {@link SubscriptionConfigQueue.SubscriptionConfig} is successfully enqueued.
     *
     * @throws IOException
     */
    public boolean enqueueSubscriptionConfig(SubscriptionConfigQueue.SubscriptionConfig subscriptionConfig) throws IOException {
        // The requests are added to a queue and processed async. A
        // status notification will be pushed to the client async.
        return SubscriptionConfigQueue.add(subscriptionConfig);
    }

    /**
     * Returns true if the dispatcherId associated with this request exists and a ping event is successfully dispatched to it.
     *
     * @throws IOException
     */
    public boolean ping(HttpServletRequest request) throws IOException {
        String dispatcherId = request.getParameter("dispatcherId");

        if (dispatcherId != null) {
            EventDispatcher dispatcher = EventDispatcherFactory.getDispatcher(dispatcherId, request.getSession());
            if (dispatcher != null) {
                try {
                    dispatcher.dispatchEvent("pingback", "ack");
                } catch (ServletException e) {
                    LOGGER.log(Level.FINE, "Failed to send pingback to dispatcher " + dispatcherId + ".", e);
                    return false;
                }
            }
        }

        return true;
    }
}
