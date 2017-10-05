package com.cloudbees.analytics.rest.server.api;

import com.cloudbees.analytics.sse.SseServlet;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.session.DefaultSessionIdManager;
import org.eclipse.jetty.server.session.SessionHandler;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHandler;
import org.jenkinsci.plugins.pubsub.BasicMessage;
import org.jenkinsci.plugins.pubsub.PubsubBus;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.ClientRequestContext;
import javax.ws.rs.client.ClientRequestFilter;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.NewCookie;
import javax.ws.rs.core.Response;
import javax.ws.rs.sse.InboundSseEvent;
import javax.ws.rs.sse.SseEventSource;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.Assert.assertEquals;

public class SseServletIT {
    private static final Logger LOGGER = LogManager.getLogger(SseServletIT.class);

    private static Server server;

    @BeforeClass
    public static void startJetty() throws Exception {
        server = new Server(8080);

        final ServletContextHandler contextHandler = new ServletContextHandler(server, "/*");

        final ServletHandler servletHandler = new ServletHandler();
        servletHandler.addServletWithMapping(SseServlet.class, "/sse-gateway/*");
        servletHandler.initialize();
        contextHandler.setServletHandler(servletHandler);

        final SessionHandler sessionHandler = new SessionHandler();
        sessionHandler.setSessionIdManager(new DefaultSessionIdManager(server));
        contextHandler.setSessionHandler(sessionHandler);

        server.setHandler(contextHandler);
        server.start();
    }

    @AfterClass
    public static void stopJetty() throws Exception {
        server.stop();
    }

    @Test
    public void testAll() throws Exception {
        final Client client = ClientBuilder.newBuilder().build();

        final String clientId = "someClientId";
        final String someChannel = "someChannel";
        final String connectUrl = "http://localhost:8080/sse-gateway/connect?clientId=" + clientId;

        LOGGER.info("connecting to SSE backend at url={}", connectUrl);
        final WebTarget connectTarget = client.target(connectUrl);
        final Response connectResponse = connectTarget.request().buildGet().invoke();
        final Map<String, NewCookie> connectCookies = connectResponse.getCookies();
        LOGGER.info("received connect response={}, session={}", connectResponse.readEntity(String.class), connectCookies);

        final JSONObject configuration = new JSONObject();
        configuration.element("dispatcherId", clientId);
        final JSONArray channels = new JSONArray();
        final JSONObject channel = new JSONObject();
        channel.element("jenkins_channel", someChannel);
        channels.element(channel);
        configuration.element("subscribe", channels);

        final String configureUrl = "http://localhost:8080/sse-gateway/configure?clientId=" + clientId;
        LOGGER.info("configuring SSE at url={}, clientId={}, configuration={}", connectUrl, clientId, configuration.toString());
        final WebTarget configureTarget = client.target(configureUrl);
        configureTarget.register(new CookieSettingFilter(connectCookies));
        final Response configureResponse = configureTarget.request().buildPost(Entity.json(configuration.toString())).invoke();
        LOGGER.info("received configuration response={}", configureResponse.readEntity(String.class));

        final String listenUrl = "http://localhost:8080/sse-gateway/listen/" + clientId;
        LOGGER.info("listening with SSE client at url={}, clientId={}", listenUrl, clientId);
        final WebTarget target = client.target(listenUrl);
        target.register(new CookieSettingFilter(connectCookies));

        final List<InboundSseEvent> events = new ArrayList<>();
        // jersey SSE client
        final SseEventSource sseEventSource = SseEventSource.target(target).build();
        sseEventSource.register(new Consumer<InboundSseEvent>() {
            @Override
            public void accept(final InboundSseEvent event) {
                LOGGER.info("received event, name={}, data={}", event.getName(), event.readData(String.class));
                events.add(event);
            }
        });
        sseEventSource.open();

        final String pingUrl = "http://localhost:8080/sse-gateway/ping?dispatcherId=" + clientId;
        LOGGER.info("pinging SSE backend url={}, clientId={}", pingUrl, clientId);
        final WebTarget pingTarget = client.target(pingUrl);
        pingTarget.register(new CookieSettingFilter(connectCookies));
        final Response pingResponse = pingTarget.request().buildGet().invoke();
        LOGGER.info("received ping response={}", pingResponse.readEntity(String.class));

        LOGGER.info("sending message to subscribers to channel={}", someChannel);
        final BasicMessage message = new BasicMessage();
        message.setChannelName(someChannel);
        message.set("jenkins_channel", someChannel);
        message.setEventName("job_crud_created");
        message.set("jenkins_event", "job_crud_created");
        message.set("foo", "bar");
        PubsubBus.getBus().publish(message);

        Thread.sleep(100);

        CompletableFuture.supplyAsync(() -> {
            while(events.size() != 3) { }
            return null;
        }).thenRun(() -> {
            assertEquals(3, events.size());
            assertEquals("open", events.get(0).getName());
            assertEquals("pingback", events.get(1).getName());
            assertEquals("someChannel", events.get(2).getName());
        }).get(10, TimeUnit.SECONDS);

        sseEventSource.close();
    }
}

class CookieSettingFilter implements ClientRequestFilter {
    private final Map<String, NewCookie> connectCookies;

    CookieSettingFilter(final Map<String, NewCookie> cookies) {
        this.connectCookies = cookies;
    }

    @Override
    public void filter(final ClientRequestContext requestContext) throws IOException {
        final List<Object> cookies = new ArrayList<>();
        for (final Map.Entry<String, NewCookie> cookie : connectCookies.entrySet()) {
            cookies.add(cookie.getValue().toString());
        }
        requestContext.getHeaders().put("Cookie", cookies);
    }
}
