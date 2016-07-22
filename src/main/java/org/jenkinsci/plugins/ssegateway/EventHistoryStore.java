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
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.commons.io.FileUtils;
import org.jenkins.pubsub.Message;
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
    private static long expiresAfter = (1000 * 60 * 3); // default of 3 minutes
    private static Map<String, File> channelDirs = new ConcurrentHashMap<>();
    private static Timer autoExpireTimer;

    static void setHistoryRoot(@Nonnull File historyRoot) throws IOException {
        if (!historyRoot.exists()) {
            if (!historyRoot.mkdirs()) {
                throw new IOException(String.format("Unexpected error creating historyRoot dir %s. Check permissions etc.", historyRoot.getAbsolutePath()));
            }
        }
        EventHistoryStore.historyRoot = historyRoot;
    }

    static void setExpiryMillis(long expiresAfterMillis) {
        EventHistoryStore.expiresAfter = expiresAfterMillis;
    }

    public static void store(@Nonnull Message message) {
        try {
            String channelName = message.getChannelName();
            String eventUUID = message.getEventUUID();
            File channelDir = getChannelDir(channelName);
            File eventFile = new File(channelDir, eventUUID + ".json");
        
            FileUtils.writeStringToFile(eventFile, message.toJSON(), "UTF-8");
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Unexpected error persisting EventHistoryStore entry file.", e);
        }
    }
    
    public static String getChannelEvent(@Nonnull String channelName, @Nonnull String eventUUID) throws IOException {
        File channelDir = getChannelDir(channelName);
        File eventFile = new File(channelDir, eventUUID + ".json");
        
        if (eventFile.exists()) {
            return FileUtils.readFileToString(eventFile, "UTF-8");
        } else {
            return null;
        }
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
    
    private static void deleteAllFilesInDir(File dir, Long olderThan) throws IOException {
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
        
        // Set up a timer that runs the DeleteStaleHistoryTask 3 times over
        // duration of the event expiration timeout. By default this will be
        // every minute i.e. in that case, events are never left lying around
        // for more than 1 minute past their expiration.
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
}
