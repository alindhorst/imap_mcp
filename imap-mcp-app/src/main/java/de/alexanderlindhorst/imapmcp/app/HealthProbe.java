package de.alexanderlindhorst.imapmcp.app;

final class HealthProbe {
    static int probe(int port, int timeoutMillis) {
        return 1;
    }

    static int port(String env) {
        return 0;
    }
}
