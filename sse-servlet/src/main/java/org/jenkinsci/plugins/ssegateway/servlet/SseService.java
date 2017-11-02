package org.jenkinsci.plugins.ssegateway.servlet;

import org.apache.logging.log4j.*;
import org.jenkinsci.plugins.ssegateway.*;

import java.io.IOException;
import java.nio.file.Files;

public class SseService extends SseServletBase {
    private static final Logger LOGGER = LogManager.getLogger(SseService.class);

    public void init() {
        SubscriptionConfigQueue.start();
        try {
            EventHistoryStore.setHistoryRoot(Files.createTempDirectory("sse-events").toFile());
            EventHistoryStore.enableAutoDeleteOnExpire();
        } catch (final IOException e) {
            LOGGER.fatal("Unexpected error setting EventHistoryStore location", e);
        }
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            @Override
            public void run() {
                SubscriptionConfigQueue.stop();
            }
        }));
    }
}
