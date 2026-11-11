package de.alexanderlindhorst.imapmcp.it;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Canary scan: generated secrets must not appear in any captured output. Reports key names, never values. */
final class DeployedSecretScan {

    private static final int MIN_LENGTH = 16;

    private DeployedSecretScan() {
    }

    /** The values of the secret-looking keys (PASSWORD, SECRET, KEY, KEK) of an env file, name to value. */
    static Map<String, String> secrets(Path envFile) throws IOException {
        Map<String, String> result = new TreeMap<>();
        for (String line : Files.readAllLines(envFile)) {
            int eq = line.indexOf('=');
            if (eq <= 0 || line.startsWith("#")) {
                continue;
            }
            String name = line.substring(0, eq);
            String value = line.substring(eq + 1);
            boolean secretLike = name.contains("PASSWORD") || name.contains("SECRET") || name.contains("KEY")
                    || name.startsWith("KEK");
            if (secretLike && value.length() >= MIN_LENGTH) {
                result.put(name, value);
            }
        }
        return result;
    }

    static Collection<String> secretValues(Path envFile) throws IOException {
        return secrets(envFile).values();
    }

    /** The base64 body lines of a PEM file: each is a canary of the key. */
    static List<String> pemLines(Path pemFile) throws IOException {
        List<String> result = new ArrayList<>();
        for (String line : Files.readAllLines(pemFile)) {
            if (!line.startsWith("-----") && line.length() >= MIN_LENGTH) {
                result.add(line.strip());
            }
        }
        return result;
    }

    static List<String> leaks(String text, Map<String, String> secretsByName) {
        List<String> found = new ArrayList<>();
        secretsByName.forEach((name, value) -> {
            if (text.contains(value)) {
                found.add(name);
            }
        });
        return found;
    }
}
