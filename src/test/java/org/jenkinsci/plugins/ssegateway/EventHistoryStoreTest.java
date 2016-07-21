package org.jenkinsci.plugins.ssegateway;

import net.sf.json.JSONObject;
import org.jenkins.pubsub.EventProps;
import org.jenkins.pubsub.SimpleMessage;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;

/**
 * @author <a href="mailto:tom.fennelly@gmail.com">tom.fennelly@gmail.com</a>
 */
public class EventHistoryStoreTest {
    
    private static final String CHANNEL_NAME = "job";
    private static int IOTA = 0;

    private File historyRoot = new File("./target/historyRoot");
    
    @Before
    public void setup() throws IOException {
        EventHistoryStore.setHistoryRoot(historyRoot);
        EventHistoryStore.setExpiryMillis(3000); // a 3s history window ... anything older than that would be "stale"
        EventHistoryStore.deleteAllHistory();
    }

    @Test
    public void test_store_count_delete() throws Exception {
        Assert.assertEquals(0, EventHistoryStore.getChannelEventCount("job"));
        storeMessages(15, 100);
        Assert.assertEquals(15, EventHistoryStore.getChannelEventCount("job"));
        
        // delete and check count again
        EventHistoryStore.deleteAllHistory();
        Assert.assertEquals(0, EventHistoryStore.getChannelEventCount("job"));
        storeMessages(15, 100);
        Assert.assertEquals(15, EventHistoryStore.getChannelEventCount("job"));
        EventHistoryStore.deleteAllHistory();
        Assert.assertEquals(0, EventHistoryStore.getChannelEventCount("job"));
    }

    @Test
    public void test_delete_stale_events() throws Exception {
        EventHistoryStore.deleteAllHistory();
        Assert.assertEquals(0, EventHistoryStore.getChannelEventCount("job"));
        
        // Store 6 events, each 1 second apart.
        storeMessages(6, 1000);
        
        // sleep for split, just to move the window a little more.
        Thread.sleep(200);
        
        // The "History Window" was set to 3 seconds in the @Before,
        // so if we delete stale events now, we should delete at least
        // some of those files, but not them all.
        Assert.assertEquals(6, EventHistoryStore.getChannelEventCount("job"));
        EventHistoryStore.deleteStaleHistory();
        Assert.assertTrue(EventHistoryStore.getChannelEventCount("job") > 0);
        Assert.assertTrue(EventHistoryStore.getChannelEventCount("job") < 6);
        
        // sleep for 6 seconds now, run the delete again and make sure
        // the rest of the files are deleted because they should all be stale
        // by then.
        Thread.sleep(6000);
        EventHistoryStore.deleteStaleHistory();
        Assert.assertEquals(0, EventHistoryStore.getChannelEventCount("job"));
    }

    @Test
    public void test_get_event() throws Exception {
        SimpleMessage message = createMessage();
        
        EventHistoryStore.store(message);
        
        String eventAsString = EventHistoryStore.getChannelEvent("job", message.getEventUUID());
        Assert.assertNotNull(eventAsString);
        JSONObject eventAsJSON = JSONObject.fromObject(eventAsString);
        Assert.assertEquals(message.getEventUUID(), eventAsJSON.getString(EventProps.Jenkins.jenkins_event_uuid.name()));
    }
    
    private void storeMessages(int numMessages, long timeGap) throws Exception {
        for (int i = 0; i < numMessages; i++) {
            if (timeGap > 0) {
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