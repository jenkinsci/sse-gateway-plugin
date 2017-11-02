package com.cloudbees.analytics.rest.server.api;

import net.sf.json.*;
import org.apache.commons.lang.RandomStringUtils;
import org.apache.logging.log4j.*;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.session.*;
import org.eclipse.jetty.servlet.*;
import org.jenkinsci.plugins.pubsub.*;
import org.jenkinsci.plugins.ssegateway.servlet.SseServlet;
import org.junit.*;

import javax.ws.rs.client.*;
import javax.ws.rs.core.*;
import javax.ws.rs.sse.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

import static org.junit.Assert.*;

public class SseServletIT {
    private static final Logger LOGGER = LogManager.getLogger(SseServletIT.class);

    private static Server server;
    private Client client;
    private int servletPort;

    @Before
    public void setUp() throws Exception {
        client = ClientBuilder.newBuilder().build();
    }

    /**
     * Default maven profile adds guava pubsub provider.
     */
    @Test
    public void testGuavaProvider_embeddedJetty() throws Exception {
        startJetty();

        servletPort = 8080;
        final String clientId = "someClientId";

        final String connectUrl = String.format("http://localhost:%d/sse-gateway/connect?clientId=%s", servletPort, clientId);

        sseServletBaseIT(connectUrl);

        stopJetty();
    }

    /**
     * Activate redis-provider profile to use RedisPubsub provider.
     */
    @Test
    public void testRedisProvider() throws Exception {
        servletPort = 38080;
        final String clientId = "someClientId";

        // point at sse-servlet running with redis pubsub provider installed
        // TODO: possible to run this test in embedded server?
        final String connectUrl = String.format("http://localhost:%d/sse-gateway/connect?clientId=%s", servletPort, clientId);

        sseServletBaseIT(connectUrl);
    }

    private void sseServletBaseIT(final String connectUrl) throws MessageException, InterruptedException, java.util.concurrent.ExecutionException, java.util.concurrent.TimeoutException {
        final String clientId = "someClientId";
        final String someChannel = "someChannel";

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

        final String configureUrl = String.format("http://localhost:%d/sse-gateway/configure?clientId=%s", servletPort, clientId);
        LOGGER.info("configuring SSE at url={}, clientId={}, configuration={}", configureUrl, clientId, configuration.toString());
        final WebTarget configureTarget = client.target(configureUrl);
        configureTarget.register(new CookieSettingFilter(connectCookies));
        final Response configureResponse = configureTarget.request().buildPost(Entity.json(configuration.toString())).invoke();
        LOGGER.info("received configuration response={}", configureResponse.readEntity(String.class));

        final String listenUrl = String.format("http://localhost:%d/sse-gateway/listen/%s", servletPort, clientId);
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

        final String pingUrl = String.format("http://localhost:%d/sse-gateway/ping?dispatcherId=%s", servletPort, clientId);
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

        message.setProperty("prop", "1");
        PubsubBus.getBus().publish(message);

        message.setProperty("prop", "2");
        PubsubBus.getBus().publish(message);

        message.setProperty("prop", "3");
        PubsubBus.getBus().publish(message);

        Thread.sleep(100);

        CompletableFuture.supplyAsync(() -> {
            while(events.size() != 5) { }
            return null;
        }).thenRun(() -> {
            assertEquals(5, events.size());

            assertEquals("open", events.get(0).getName());
            assertEquals("pingback", events.get(1).getName());

            assertEquals("someChannel", events.get(2).getName());
            assertTrue(events.get(2).readData(String.class).contains("\"prop\":\"1\""));

            assertEquals("someChannel", events.get(3).getName());
            assertTrue(events.get(3).readData(String.class).contains("\"prop\":\"2\""));

            assertEquals("someChannel", events.get(4).getName());
            assertTrue(events.get(4).readData(String.class).contains("\"prop\":\"3\""));
        }).get(10, TimeUnit.SECONDS);

        sseEventSource.close();
    }

    private static void startJetty() throws Exception {
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

    private static void stopJetty() throws Exception {
        server.stop();
    }

    @Test
    public void testRedisProvider_multipleServlets() throws Exception {
        final int jettyPort1 = 18080;
        final int jettyPort2 = 28080;
        final int jettyPort3 = 38080;

        final Client client1 = ClientBuilder.newBuilder().executorService(Executors.newCachedThreadPool()).build();


        final String clientId = RandomStringUtils.randomAlphabetic(10);
        final String channelName = RandomStringUtils.randomAlphabetic(10);
        LOGGER.info("clientId={}, channelName={}", clientId, channelName);

        final String connectUrl = String.format("http://localhost:%d/sse-gateway/connect?clientId=%s", jettyPort1, clientId);

        LOGGER.info("connecting to SSE backend at url={}", connectUrl);
        final WebTarget connectTarget = client1.target(connectUrl);
        final Response connectResponse = connectTarget.request().buildGet().invoke();
        final Map<String, NewCookie> connectCookies = connectResponse.getCookies();
        LOGGER.info("got cookies={}", connectCookies);

        final Client client2 = ClientBuilder.newBuilder().executorService(Executors.newCachedThreadPool()).build();
        final Client client3 = ClientBuilder.newBuilder().executorService(Executors.newCachedThreadPool()).build();

        configure(client1, clientId, channelName, jettyPort1, connectCookies);
        configure(client2, clientId, channelName, jettyPort2, connectCookies);
        configure(client3, clientId, channelName, jettyPort3, connectCookies);

        listen(client1, clientId, jettyPort1, connectCookies);
        listen(client2, clientId, jettyPort2, connectCookies);
        listen(client3, clientId, jettyPort3, connectCookies);

        LOGGER.info("sending message to subscribers to channel={}", channelName);
        final BasicMessage message = new BasicMessage();
        message.setChannelName(channelName);
        message.set("jenkins_channel", channelName);
        message.setEventName("job_crud_created");
        message.set("jenkins_event", "job_crud_created");
        message.set("foo", "bar");

        message.setProperty("prop", "1");
        PubsubBus.getBus().publish(message);

        message.setProperty("prop", "2");
        PubsubBus.getBus().publish(message);

        message.setProperty("prop", "3");
        PubsubBus.getBus().publish(message);

        Thread.sleep(1000);
    }

    private void configure(final Client client, final String clientId, final String someChannel, final int port, final Map<String, NewCookie> connectCookies) {
        final JSONObject configuration = new JSONObject();
        configuration.element("dispatcherId", clientId);
        final JSONArray channels = new JSONArray();
        final JSONObject channel = new JSONObject();
        channel.element("jenkins_channel", someChannel);
        channels.element(channel);
        configuration.element("subscribe", channels);

        final String configureUrl = String.format("http://localhost:%d/sse-gateway/configure?clientId=%s", port, clientId);
        LOGGER.info("configuring SSE at url={}, clientId={}, configuration={}", configureUrl, clientId, configuration.toString());
        final WebTarget configureTarget = client.target(configureUrl);
        configureTarget.register(new CookieSettingFilter(connectCookies));
        final Response configureResponse = configureTarget.request().buildPost(Entity.json(configuration.toString())).invoke();
        LOGGER.info("received configuration response={}", configureResponse.readEntity(String.class));
    }

    private void listen(final Client client, final String clientId, final int port, final Map<String, NewCookie> connectCookies) throws InterruptedException, ExecutionException, TimeoutException {
        final String listenUrl = String.format("http://localhost:%d/sse-gateway/listen/%s", port, clientId);
        LOGGER.info("listening with SSE client at url={}, clientId={}", listenUrl, clientId);
        final WebTarget target = client.target(listenUrl);
        target.register(new CookieSettingFilter(connectCookies));

        final List<InboundSseEvent> events = new ArrayList<>();
        // jersey SSE client
        final SseEventSource sseEventSource = SseEventSource.target(target).build();
        sseEventSource.register(event -> {
            LOGGER.info("received event, name={}, data={}", event.getName(), event.readData(String.class));
            events.add(event);
        });
        sseEventSource.open();

        CompletableFuture.supplyAsync(() -> {
            LOGGER.info("waiting for events...");
            while(events.size() < 4) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
            return null;
        }).thenRun(() -> {
            LOGGER.info("checking events...");
            assertEquals(4, events.size());

            assertEquals("open", events.get(0).getName());

            assertEquals("someChannel", events.get(1).getName());
            assertTrue(events.get(1).readData(String.class).contains("\"prop\":\"1\""));

            assertEquals("someChannel", events.get(2).getName());
            assertTrue(events.get(2).readData(String.class).contains("\"prop\":\"2\""));

            assertEquals("someChannel", events.get(3).getName());
            assertTrue(events.get(3).readData(String.class).contains("\"prop\":\"3\""));
        });
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
