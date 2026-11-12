package de.alexanderlindhorst.imapmcp.it;

/**
 * What containers.txt says about one container: service, state, status text, health status and exit code, nothing
 * else (no environment, command, labels or mounts).
 */
record ContainerFacts(String service, String state, String status, String health, long exitCode) {

    String line() {
        return "service=" + flat(service) + " state=" + flat(state) + " status=\"" + flat(status) + "\" health="
                + (health == null || health.isBlank() ? "none" : flat(health)) + " exit=" + exitCode;
    }

    private static String flat(String s) {
        return s == null ? "" : s.replaceAll("[\\r\\n]+", " ");
    }
}
