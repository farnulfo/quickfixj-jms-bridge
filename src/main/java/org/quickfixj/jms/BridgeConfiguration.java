package org.quickfixj.jms;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

public final class BridgeConfiguration {
    public static final String CONNECTION_FACTORY = "jms.connectionFactory";
    public static final String OUTBOUND_DESTINATION = "jms.outboundDestination";
    public static final String INBOUND_DESTINATION = "jms.inboundDestination";
    public static final String FIX_SETTINGS = "bridge.fixSettings";
    public static final String REQUIRE_FIX_LOGON = "bridge.requireFixLogon";

    private final Properties properties;

    private BridgeConfiguration(Properties properties) {
        this.properties = properties;
    }

    public static BridgeConfiguration load(String path) throws IOException {
        Properties properties = new Properties();
        try (InputStream input = new FileInputStream(path)) {
            properties.load(input);
        }
        BridgeConfiguration configuration = new BridgeConfiguration(properties);
        configuration.required(CONNECTION_FACTORY);
        configuration.required(OUTBOUND_DESTINATION);
        configuration.required(INBOUND_DESTINATION);
        configuration.required(FIX_SETTINGS);
        return configuration;
    }

    public String required(String key) {
        String value = properties.getProperty(key);
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("Missing required bridge property: " + key);
        }
        return value.trim();
    }

    public boolean requireFixLogon() {
        return Boolean.parseBoolean(properties.getProperty(REQUIRE_FIX_LOGON, "true"));
    }
}
