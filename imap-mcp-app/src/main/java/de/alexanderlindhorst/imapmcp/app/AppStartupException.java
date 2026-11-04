package de.alexanderlindhorst.imapmcp.app;

/** Startup wiring failed. The message says what to fix and never contains a secret value. */
public class AppStartupException extends RuntimeException {

    public AppStartupException(String message) {
        super(message);
    }

    public AppStartupException(String message, Throwable cause) {
        super(message, cause);
    }
}
