package org.jenkinsci.plugins.ssegateway;

import static org.junit.jupiter.api.Assertions.*;

import hudson.Functions;
import net.sf.json.JSONObject;
import org.jenkinsci.plugins.pubsub.EventProps;
import org.jenkinsci.plugins.pubsub.SimpleMessage;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;

/**
 * @author <a href="mailto:tom.fennelly@gmail.com">tom.fennelly@gmail.com</a>
 */
class EventHistoryStoreTest {

    private static final String CHANNEL_NAME = "job";
    private static int IOTA = 0;

    private final File historyRoot = new File("./target/historyRoot");

    @BeforeEach
    void setUp() throws Exception {
        EventHistoryStore.setHistoryRoot(historyRoot);
        EventHistoryStore.setExpiryMillis(3000); // a 3s history window ... anything older than that would be "stale"
        EventHistoryStore.deleteAllHistory();
    }

    @Test
    void test_store_count_delete() throws Exception {
        assertEquals(0, EventHistoryStore.getChannelEventCount("job"));
        storeMessages(15, 100);
        assertEquals(15, EventHistoryStore.getChannelEventCount("job"));

        // delete and check count again
        EventHistoryStore.deleteAllHistory();
        assertEquals(0, EventHistoryStore.getChannelEventCount("job"));
        storeMessages(15, 100);
        assertEquals(15, EventHistoryStore.getChannelEventCount("job"));
        EventHistoryStore.deleteAllHistory();
        assertEquals(0, EventHistoryStore.getChannelEventCount("job"));
    }

    @Test
    void test_delete_stale_events() throws Exception {
        // SKip the test on Windows ci.jenkins.io agents because it
        // requires very different timing constraints. Assumed to be
        // an issue with different file system performance in that
        // environment.  Does not fail tests on a Windows machine used
        // for development.
        Assumptions.assumeFalse(Functions.isWindows() && System.getenv("CI") != null);
        EventHistoryStore.deleteAllHistory();
        assertEquals(0, EventHistoryStore.getChannelEventCount("job"));

        // We use this guy to control the pauses in the test.
        // Note that the expiry was set to 3 seconds in the @Before,
        WaitTimer waitTimer = new WaitTimer();

        // Store 6 events, each 1 second apart.
        // Should complete after about 6 seconds.
        // The last of these messages should expire
        // at about 9 seconds on the clock.
        storeMessages(6, 1000);

        // We're at about 6 seconds now, let's wait 'til about
        // 7 seconds on the clock before proceeding
        waitTimer.waitUntil(7000);

        // The expiry was set to 3 seconds in the @Before,
        // so if we delete stale events now, we should delete
        // some of those files, but not them all.
        assertEquals(6, EventHistoryStore.getChannelEventCount("job"));
        EventHistoryStore.deleteStaleHistory();
        assertTrue(EventHistoryStore.getChannelEventCount("job") > 0);
        long count = EventHistoryStore.getChannelEventCount("job");
        assertTrue( count < 6, "count is " + count);

        // All of those mesages should be stale after about 9 seconds, so
        // lets wait 'till 10 seconds on the clock and run the delete again
        // and make sure they are all gone.
        waitTimer.waitUntil(10000);
        EventHistoryStore.deleteStaleHistory();
        assertEquals(0, EventHistoryStore.getChannelEventCount("job"));
    }

    @Test
    void test_get_event() throws Exception {
        SimpleMessage message = createMessage();

        EventHistoryStore.store(message);

        String eventAsString = EventHistoryStore.getChannelEvent("job", message.getEventUUID());
        assertNotNull(eventAsString);
        JSONObject eventAsJSON = JSONObject.fromObject(eventAsString);
        assertEquals(message.getEventUUID(), eventAsJSON.getString(EventProps.Jenkins.jenkins_event_uuid.name()));
    }

    @Test
    void test_autoDeleteOnExpire() throws Exception {
        // SKip the test on Windows ci.jenkins.io agents because it
        // requires very different timing constraints. Assumed to be
        // an issue with different file system performance in that
        // environment.  Does not fail tests on a Windows machine used
        // for development.
        Assumptions.assumeFalse(Functions.isWindows() && System.getenv("CI") != null);
        EventHistoryStore.enableAutoDeleteOnExpire();

        // In this test, we go through a few "phases" of adding messages to the
        // store, punctuated by gaps, which we control using a WaitTimer (see below).
        // What we're trying to test is that after 3 second (or so - see @Before)
        // stale messages start being automatically deleted.

        try {
            EventHistoryStore.deleteAllHistory();
            assertEquals(0, EventHistoryStore.getChannelEventCount("job"));

            // We use this guy to control the pauses in the test.
            WaitTimer waitTimer = new WaitTimer();

            // BATCH 1: Store some messages
            // These should expire a little after the 3 second mark (0 + 3).
            storeMessages(50);
            assertEquals(50, EventHistoryStore.getChannelEventCount("job"));

            // sleep 'til 2 seconds on the clock
            waitTimer.waitUntil(2000);

            // BATCH 2: Store some messages
            storeMessages(50);
            // These should expire a little after the 5 second mark (2 + 3).
            assertEquals(100, EventHistoryStore.getChannelEventCount("job"));

            // sleep 'til 4.5 seconds on the clock
            waitTimer.waitUntil(4500);
            // we are about 4 seconds in now and there should be less than 100 messages
            // in the store because the 3 second expiration has completed for the first 50.
            // Windows tests on ci.jenkins.io show that the message store is not reduced at this
            // point, even though the Linux message store is reduced as expected.

            // BATCH 3: Store some messages
            // These should expire a little after the 7.5 second mark (4.5 + 3).
            storeMessages(50);

            // NOW ... how many should be in the store?
            // We have added 3 batches of 50 (total 150), but BATCH 1 would have
            // expired after ~ 3 seconds, so we should just have BATCHes 2 and 3
            // in the store i.e. 100. Doing an exact match against 100 might be a
            // be a bit flaky though, so lets just make sure some of the messages
            // have expired, but not all.
            assertTrue(EventHistoryStore.getChannelEventCount("job") > 0);
            long count = EventHistoryStore.getChannelEventCount("job");
            assertTrue(count < 150, "count is " + count);

            // Now lets wait until after all of the messages would be expired
            // They should all expire after about 7.5 seconds (see above), but lets
            // wait for a bit after that just to make sure that the test is not flaky.
            waitTimer.waitUntil(10000); // 10 seconds
            assertEquals(0, EventHistoryStore.getChannelEventCount("job"));

        } finally {
            EventHistoryStore.disableAutoDeleteOnExpire();
        }
    }

    private void storeMessages(int numMessages) throws Exception {
        storeMessages(numMessages, 0);
    }

    private void storeMessages(int numMessages, long timeGap) throws Exception {
        for (int i = 0; i < numMessages; i++) {
            if (i > 0 && timeGap > 0) {
                Thread.sleep(timeGap);
            }
            EventHistoryStore.store(createMessage());
        }
    }

    private SimpleMessage createMessage() {
        return new SimpleMessage().setChannelName(CHANNEL_NAME)
                .setEventName("test-event")
                .set("timestamp", Long.toString(System.currentTimeMillis()))
                .set("messageNum", Integer.toString(IOTA++));
    }
}
