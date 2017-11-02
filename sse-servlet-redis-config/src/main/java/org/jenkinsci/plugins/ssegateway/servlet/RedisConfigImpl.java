package org.jenkinsci.plugins.ssegateway.servlet;

import org.apache.logging.log4j.*;
import org.jenkinsci.plugins.pubsub.RedisConfig;

import java.io.*;
import java.util.Properties;

/**
 * Redis configuration service provider for the RedisPubsubBus.
 */
public class RedisConfigImpl implements RedisConfig {
    private static final Logger LOGGER = LogManager.getLogger(RedisConfigImpl.class);

    private static final String SSE_SERVLET_REDIS_HOST = "SSE_SERVLET_REDIS_HOST";
    private static final String SSE_SERVLET_REDIS_PORT = "SSE_SERVLET_REDIS_PORT";
    private static final String SSE_SERVLET_REDIS_SSL = "SSE_SERVLET_REDIS_SSL";

    private static final Properties envProperties;

    static {
        String envConfigFileName = "/sse-servlet.properties";

        envProperties = new Properties();
        try (InputStream inputStream = RedisConfigImpl.class.getResourceAsStream(envConfigFileName)) {
            envProperties.load(inputStream);
        } catch (IOException e) {
            throw new IllegalStateException(String.format("Error reading environment configuration file '%s' from classpath: ", envConfigFileName), e);
        }
    }

    @Override
    public String getRedisHost() {
        return getConfig(SSE_SERVLET_REDIS_HOST, "127.0.0.1");
    }

    @Override
    public int getRedisPort() {
        return getIntConfig(SSE_SERVLET_REDIS_PORT, 6379);
    }

    @Override
    public boolean isRedisSSL() {
        return getBooleanConfig(SSE_SERVLET_REDIS_SSL, false);
    }

    private static String getConfig(String name, String defaultVal) {
        String value;

        value = System.getProperty(name);
        if (value != null) {
            return value;
        }
        value = System.getenv(name);
        if (value != null) {
            return value;
        }

        if (envProperties != null) {
            value = envProperties.getProperty(name);
            if (value != null) {
                return value;
            }
        }

        return defaultVal;
    }

    public static boolean getBooleanConfig(String name, boolean defaultVal) {
        return getConfig(name, Boolean.toString(defaultVal)).equals("true");
    }

    public static int getIntConfig(String name, int defaultVal) {
        try {
            return Integer.valueOf(getConfig(name, Integer.toString(defaultVal)));
        } catch (Exception e) {
            LOGGER.warn("Unexpected error parsing property '{}' as a Integer. Returning defaultVal %d.", name, defaultVal, e);
            return defaultVal;
        }
    }
}
