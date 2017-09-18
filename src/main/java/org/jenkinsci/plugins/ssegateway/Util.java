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

import hudson.Functions;
import net.sf.json.JSONObject;
import org.apache.commons.io.IOUtils;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.util.HashMap;
import java.util.Map;

/**
 * Internal utility methods.
 * @author <a href="mailto:tom.fennelly@gmail.com">tom.fennelly@gmail.com</a>
 */
@Restricted(NoExternalUse.class)
public class Util {

    private Util() {
    }

    public static JSONObject readJSONPayload(HttpServletRequest request) throws IOException {
        String characterEncoding = request.getCharacterEncoding();
        
        if (characterEncoding == null) {
            characterEncoding = "UTF-8";
        }
        
        Reader streamReader = new InputStreamReader(request.getInputStream(), characterEncoding);
        try {
            String payloadAsString = IOUtils.toString(streamReader);
            return JSONObject.fromObject(payloadAsString);
        } finally {
            IOUtils.closeQuietly(streamReader);
        }
    }

    private static Boolean isTestEnv = null;
    public static boolean isTestEnv() {
        if (isTestEnv != null) {
            return isTestEnv;
        }
        
        if (Functions.getIsUnitTest()) {
            isTestEnv = true;
        } else if (new File("./target/.jenkins_test").exists()) {
            isTestEnv = true;
        } else if (new File("./target/classes/" + Util.class.getName().replace(".", "/") + ".class").exists()) {
            isTestEnv = true;
        }
        
        // If there's none of the markers, then we're not
        // in a test env.
        if (isTestEnv == null){
            isTestEnv = false;
        }
        
        return isTestEnv;
    }

    public static Map<String, String> getSessionInfo(HttpSession session) {
        Map<String, String> info = new HashMap<>();
        info.put("sessionid", session.getId());
        info.put("cookieName", session.getServletContext().getSessionCookieConfig().getName());
        return info;
    }
}
