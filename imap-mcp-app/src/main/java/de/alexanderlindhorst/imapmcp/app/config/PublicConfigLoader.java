package de.alexanderlindhorst.imapmcp.app.config;

import java.nio.file.Path;
import java.util.Map;

/** Stub. */
public final class PublicConfigLoader {

    private PublicConfigLoader() {
    }

    public static PublicConfig load(Path file, Map<String, String> env) {
        throw new UnsupportedOperationException();
    }

    static PublicConfig bindYaml(String yaml, Map<String, String> env) {
        throw new UnsupportedOperationException();
    }
}
