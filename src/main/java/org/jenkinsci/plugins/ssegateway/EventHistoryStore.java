/*
 * The MIT License
 *
 * Copyright (c) 2016, CloudBees, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package org.jenkinsci.plugins.ssegateway;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.security.ACL;
import org.apache.commons.io.FileUtils;
import org.jenkins.pubsub.ChannelSubscriber;
import org.jenkins.pubsub.Message;
import org.jenkins.pubsub.PubsubBus;
import org.jenkinsci.plugins.ssegateway.sse.EventDispatcher;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Channel event message history store.
 * <p>
 * Currently stores event history in files on disk, purging them
 * as they go "stale" (after they expire).
 * 
 * @author <a href="mailto:tom.fennelly@gmail.com">tom.fennelly@gmail.com</a>
 */
@Restricted(NoExternalUse.class)
public final class EventHistoryStore {

    private static final Logger LOGGER = Logger.getLogger(EventHistoryStore.class.getName());
    
    private static File historyRoot;
    private static long expiresAfter = (1000 * 60); // default of 1 minutes
    private static Map<String, File> channelDirs = new ConcurrentHashMap<>();
    private static Timer autoExpireTimer;
    
    private static final LoadingCache<String, AtomicInteger> channelSubsCounters = CacheBuilder.<String, AtomicInteger>newBuilder().build(new CacheLoader<String, AtomicInteger>() {
        @Override
        public AtomicInteger load(String key) throws Exception {
            return new AtomicInteger(0);
        }
    });

    private static final LoadingCache<String, EventHistoryLogger> channelLoggers = CacheBuilder.<String, EventHistoryLogger>newBuilder().build(new CacheLoader<String, EventHistoryLogger>() {
        @Override
        public EventHistoryLogger load(String channelName) throws Exception {
            AtomicInteger counter = channelSubsCounters.getUnchecked(channelName);
            EventHistoryLogger logger = new EventHistoryLogger(counter);
            PubsubBus.getBus().subscribe(channelName, logger, ACL.SYSTEM, null);
            return logger;
        }
    });

    @SuppressFBWarnings(value = "LI_LAZY_INIT_STATIC", 
                justification = "internal class (marked @Restricted NoExternalUse + package private methods) - need it this way for testing.")
    static void setHistoryRoot(@Nonnull File historyRoot) throws IOException {
        // In a non-test mode, we only allow setting of the historyRoot 
        // once (during plugin init - see Endpoint class).
        if (EventHistoryStore.historyRoot != null && !Util.isTestEnv()) {
            LOGGER.log(Level.SEVERE, "Invalid attempt to change historyRoot after it has already been set. Ignoring.");
            return;
        }
        
        if (!historyRoot.exists()) {
            if (!historyRoot.mkdirs()) {
                throw new IOException(String.format("Unexpected error creating historyRoot dir %s. Check permissions etc.", historyRoot.getAbsolutePath()));
            }
        }
        EventHistoryStore.historyRoot = historyRoot;
    }

    static void setExpiryMillis(long expiresAfterMillis) {
        // In a non-test mode, we don't allow setting of the expiresAfter at all.
        if (!Util.isTestEnv()) {
            LOGGER.log(Level.SEVERE, "Invalid attempt to change expiresAfterMillis. Ignoring.");
            return;
        }
        EventHistoryStore.expiresAfter = expiresAfterMillis;
    }

    /**
     * Store a message.
     * <p>
     * <strong>Threading notes:</strong> This method is called from the {@link EventHistoryLogger}
     * instances associated with the different event channels ("job" etc). There is max 1 
     * {@link EventHistoryLogger} per channel, which means that this method gets called
     * max 1 times for each message delivered on a channel. Since each message is stored in
     * a uniquely named file (based on the message's UUID), this means that there is no risk
     * of 2 threads ever attempting to write the same message file, which means we are ok with
     * having no synchronization in/around this method. Note we give the files a temp name while
     * writing and then rename to the final name once writing is complete, protecting the retry
     * queues in the {@link EventDispatcher} instances from ever reading an event file (on retry)
     * before that event file is fully written to disk.
     * 
     * @param message The message instance to store.
     */
    static void store(@Nonnull Message message) {
        try {
            String channelName = message.getChannelName();
            String eventUUID = message.getEventUUID();
            File channelDir = getChannelDir(channelName);
            
            // We write to an intermediate file and then do a rename. This should
            // lower the chances of an EventDispacther (or other) attempting to
            // read from the file before it is fully written, as the rename should be
            // considerably "more" (depending on the platform) atomic on most platforms.
            // See threading notes above in the method javadoc.
            
            File writeEventFile = new File(channelDir, eventUUID + "_WRITE.json");
            File readEventFile = new File(channelDir, eventUUID + ".json");
        
            FileUtils.writeStringToFile(writeEventFile, message.toJSON(), "UTF-8");
            if (!writeEventFile.renameTo(readEventFile)) {
                LOGGER.log(Level.SEVERE, "Unexpected error renaming EventHistoryStore entry file to {0}.", readEventFile.getAbsolutePath());
            }
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Unexpected error persisting EventHistoryStore entry file.", e);
        }
    }
    
    public static @CheckForNull String getChannelEvent(@Nonnull String channelName, @Nonnull String eventUUID) throws IOException {
        File channelDir = getChannelDir(channelName);
        File eventFile = new File(channelDir, eventUUID + ".json");
        
        if (eventFile.exists()) {
            return FileUtils.readFileToString(eventFile, "UTF-8");
        } else {
            return null;
        }
    }
    
    public static void onChannelSubscribe(@Nonnull final String channelName) {
        if (historyRoot == null) {
            return;
        }

        // Count new subscriber
        channelSubsCounters.getUnchecked(channelName).incrementAndGet();

        // Register a logger for new subscriber
        channelLoggers.getUnchecked(channelName);
    }
    
    public static void onChannelUnsubscribe(@Nonnull String channelName) {
        if (historyRoot == null) {
            return;
        }
        channelSubsCounters.getUnchecked(channelName).decrementAndGet();
    }
    
    static int getChannelEventCount(@Nonnull String channelName) throws IOException {
        Path dirPath = Paths.get(getChannelDir(channelName).toURI());
        int count = 0;

        try (final DirectoryStream<Path> dirStream = Files.newDirectoryStream(dirPath)) {
            for (final Path entry : dirStream) {
                count++;
            }
        }
        
        return count;
    }

    /**
     * Delete all history.
     */
    static void deleteAllHistory() throws IOException {
        assertHistoryRootSet();
        deleteAllFilesInDir(EventHistoryStore.historyRoot, null);
    }

    /**
     * Delete all stale history (events that have expired).
     */
    static void deleteStaleHistory() throws IOException {
        assertHistoryRootSet();
        long olderThan = System.currentTimeMillis() - expiresAfter;
        deleteAllFilesInDir(EventHistoryStore.historyRoot, olderThan);
    }

    static File getChannelDir(@Nonnull String channelName) throws IOException {
        assertHistoryRootSet();

        File channelDir = channelDirs.get(channelName);
        if (channelDir == null) {
            channelDir = new File(historyRoot, channelName);
            channelDirs.put(channelName, channelDir);
        }
        if (!channelDir.exists()) {
            if (!channelDir.mkdirs()) {
                throw new IOException(String.format("Unexpected error creating channel event log dir %s.", channelDir.getAbsolutePath()));
            }
        }
        
        return channelDir;
    }
    
    private synchronized static void deleteAllFilesInDir(File dir, Long olderThan) throws IOException {
        Path dirPath = Paths.get(dir.toURI());
        
        try (final DirectoryStream<Path> dirStream = Files.newDirectoryStream(dirPath)) {
            for (final Path entry : dirStream) {
                File file = entry.toFile();
                if (file.isDirectory()) {
                    deleteAllFilesInDir(file, olderThan);
                }
                if (olderThan == null || file.lastModified() < olderThan) {
                    if (!file.delete()) {
                        LOGGER.log(Level.SEVERE, "Error deleting file " + file.getAbsolutePath());
                    }
                }
            }
        }
    }

    private static void assertHistoryRootSet() {
        if (historyRoot == null) {
            throw new IllegalStateException("'historyRoot' not set. Check for earlier initialization errors.");
        }
    }
    
    public synchronized static void enableAutoDeleteOnExpire() {
        if (autoExpireTimer != null) {
            return;
        }
        
        // Set up a timer that runs the DeleteStaleHistoryTask 3 times over the
        // duration of the event expiration timeout. By default this will be
        // every 20 seconds i.e. in that case, events are never left lying around
        // for more than 20 seconds past their expiration.
        long taskSchedule = expiresAfter / 3;
        autoExpireTimer = new Timer();
        autoExpireTimer.schedule(new DeleteStaleHistoryTask(), taskSchedule, taskSchedule);
        Runtime.getRuntime().addShutdownHook(new Thread() {
            @Override
            public void run() {
                disableAutoDeleteOnExpire();
            }
        });
    }
    
    public synchronized static void disableAutoDeleteOnExpire() {
        if (autoExpireTimer == null) {
            return;
        }
        autoExpireTimer.cancel();
        autoExpireTimer = null;
    }
    
    private static class DeleteStaleHistoryTask extends TimerTask {
        @Override
        public void run() {
            try {
                deleteStaleHistory();
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Error deleting stale/expired events from EventHistoryStore.", e);
            }
        }
    }

    /**
     * This class is used to listen for and log/store events that are being deivered
     * on any channels for which there are subscriptions ({@link EventDispatcher}).
     * We do this here because we do not want every {@link EventDispatcher} taking care
     * of the storing of events that might need to be retried. Instead, we have one
     * listener that does that (an instance of this class) and then all the
     * {@link EventDispatcher} listeners just hold a retryQueue that contains references
     * to the event UUIDs and use that to access the {@link EventHistoryStore} to get
     * the actual message when doing the retry. So, one listener storing the events for
     * all the {@link EventDispatcher} instances.
     * <p>
     * Note, we never remove these listeners even if the channel in question no
     * longer has any active subscribers. There's no real point.
     */
    private static class EventHistoryLogger implements ChannelSubscriber {

        private final AtomicInteger channelSubsCounter;

        private EventHistoryLogger(AtomicInteger channelSubsCounter) {
            this.channelSubsCounter = channelSubsCounter;
        }

        @Override
        public void onMessage(@Nonnull Message message) {
            if (channelSubsCounter.get() > 0) {
                store(message);
            }
        }
    }
}
