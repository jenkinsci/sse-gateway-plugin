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
package org.jenkinsci.plugins.ssegateway.sse;

import org.acegisecurity.providers.UsernamePasswordAuthenticationToken;
import org.apache.commons.lang.SerializationUtils;
import org.junit.Test;

import java.io.*;

import static org.junit.Assert.assertEquals;

/**
 * See https://issues.jenkins-ci.org/browse/JENKINS-41063
 * <p>
 * Yes there are probably other more thorough tests we could write,
 * but I think these are enough.
 *
 * @author <a href="mailto:tom.fennelly@gmail.com">tom.fennelly@gmail.com</a>
 */
public class EventDispatcherSerializationTest {

    @Test
    public void test_AsynchEventDispatcher() throws IOException {
        final AsynchEventDispatcher dispatcher = new AsynchEventDispatcher(new UsernamePasswordAuthenticationToken("username", "password"));
        serialize(dispatcher);

        final byte[] serialized = SerializationUtils.serialize(dispatcher);
        final AsynchEventDispatcher deserialized = (AsynchEventDispatcher) SerializationUtils.deserialize(serialized);

        assertEquals(dispatcher.getAuthentication(), deserialized.getAuthentication());
    }

    @Test
    public void test_SynchEventDispatcher() throws IOException {
        final UsernamePasswordAuthenticationToken dispatcher = new UsernamePasswordAuthenticationToken("username", "password");
        serialize(new SynchEventDispatcher(dispatcher));
        SerializationUtils.deserialize(SerializationUtils.serialize(dispatcher));
    }

    private void serialize(EventDispatcher dispatcher) throws IOException {
        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
             ObjectOutputStream out = new ObjectOutputStream(outputStream)) {

            out.writeObject(dispatcher);
            out.flush();
        }
    }
}