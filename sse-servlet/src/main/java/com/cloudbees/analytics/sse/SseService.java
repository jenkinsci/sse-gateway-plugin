package com.cloudbees.analytics.sse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jenkinsci.plugins.ssegateway.EventHistoryStore;
import org.jenkinsci.plugins.ssegateway.SseServletBase;
import org.jenkinsci.plugins.ssegateway.SubscriptionConfigQueue;

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
