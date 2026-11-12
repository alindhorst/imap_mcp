package de.alexanderlindhorst.imapmcp.it;

/** One line of containers.txt (stub, red). */
record ContainerFacts(String service, String state, String status, String health, long exitCode) {

    String line() {
        throw new UnsupportedOperationException();
    }
}
