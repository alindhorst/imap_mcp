package de.alexanderlindhorst.imapmcp.it;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/** Canary scan: generated secrets must not appear in any captured output. Reports key names, never values. */
final class DeployedSecretScan {

    private DeployedSecretScan() {
    }

    static Map<String, String> secrets(Path envFile) throws IOException {
        throw new UnsupportedOperationException();
    }

    static Collection<String> secretValues(Path envFile) throws IOException {
        throw new UnsupportedOperationException();
    }

    static List<String> pemLines(Path pemFile) throws IOException {
        throw new UnsupportedOperationException();
    }

    static List<String> leaks(String text, Map<String, String> secretsByName) {
        throw new UnsupportedOperationException();
    }
}
