package de.alexanderlindhorst.imapmcp.app.config;

/** Startup configuration error. The message names the key path and never contains a configured value. */
public class ConfigException extends RuntimeException {

    public ConfigException(String message) {
        super(message);
    }
}
