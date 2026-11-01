package de.alexanderlindhorst.imapmcp.store.state;

/** Startup failure of the state directory. Messages never contain secret values. */
public class StateDirectoryException extends RuntimeException {

    public StateDirectoryException(String message) {
        super(message);
    }

    public StateDirectoryException(String message, Throwable cause) {
        super(message, cause);
    }
}
