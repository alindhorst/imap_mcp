package de.alexanderlindhorst.imapmcp.it;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Set;
import java.util.TreeMap;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Copy of dist/docker that a run works in, so .env and bind mounts never resolve in the source tree. State that
 * setup.sh generates (an owner's earlier run) is left out: the copy starts clean and setup.sh makes it again.
 */
final class DeployedRunCopy {

    private static final Set<String> GENERATED =
            Set.of(".env", "local-ca", "app/config.yaml", "caddy/local/certs", "truststore/truststore.p12", "jar");

    private DeployedRunCopy() {
    }

    static void copy(Path source, Path target) throws IOException {
        Files.createDirectories(target);
        try (Stream<Path> walk = Files.walk(source)) {
            for (Path p : (Iterable<Path>) walk::iterator) {
                Path rel = source.relativize(p);
                if (isGenerated(rel)) {
                    continue;
                }
                Path dest = target.resolve(rel.toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(dest);
                } else {
                    Files.copy(p, dest, StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
        }
    }

    private static boolean isGenerated(Path rel) {
        String s = rel.toString();
        for (String g : GENERATED) {
            if (s.equals(g) || s.startsWith(g + "/")) {
                return true;
            }
        }
        return false;
    }

    /** Relative path to size and modification time, to prove a tree stayed untouched. */
    static Map<String, String> snapshot(Path root) throws IOException {
        Map<String, String> result = new TreeMap<>();
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : (Iterable<Path>) walk::iterator) {
                result.put(root.relativize(p).toString(), Files.isDirectory(p) ? "dir"
                        : Files.size(p) + "@" + Files.getLastModifiedTime(p).toMillis());
            }
        }
        return result;
    }
}
