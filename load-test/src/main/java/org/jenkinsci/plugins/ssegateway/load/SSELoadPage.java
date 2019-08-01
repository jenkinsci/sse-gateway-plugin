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
package org.jenkinsci.plugins.ssegateway.load;

import hudson.Extension;
import hudson.model.RootAction;
import org.jenkins.pubsub.MessageException;
import org.jenkins.pubsub.PubsubBus;
import org.jenkins.pubsub.SimpleMessage;
import org.kohsuke.stapler.StaplerRequest;

/**
 * @author <a href="mailto:tom.fennelly@gmail.com">tom.fennelly@gmail.com</a>
 */
@Extension
public class SSELoadPage implements RootAction {

    public String getIconFileName() {
        return "green.png";
    }

    public String getDisplayName() {
        return "SSE Gateway Load Test";
    }

    public String getUrlName() {
        return "/sse-gateway-load";
    }

    public String doFireEvents(StaplerRequest request) {
        final String channelName = request.getParameter("channelName");
        final String numMessagesParam = request.getParameter("numMessages");
        final String intervalMillisParam = request.getParameter("intervalMillis");

        if (numMessagesParam != null && intervalMillisParam != null) {
            new Thread("SSELoadPage.doFireEvents") {
                @Override
                public void run() {
                    final int numMessages = new Integer(numMessagesParam);
                    final int intervalMillis = new Integer(intervalMillisParam);
                    final PubsubBus bus = PubsubBus.getBus();

                    System.out.println("Starting to send messages on channel '" + channelName + "'.");
                    for (int i = 0; i < numMessages; i++) {
                        try {
                            bus.publish(new SimpleMessage()
                                    .setChannelName(channelName)
                                    .setEventName("amessage")
                                    .set("eventId", Integer.toString(i))
                            );
                            if (intervalMillis > 0) {
                                Thread.sleep(intervalMillis);
                            }
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }
                    System.out.println("Done sending messages on channel '" + channelName + "'.");
                }
            }.start();
        }

        return "done";
    }
}
