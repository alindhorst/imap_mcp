package de.alexanderlindhorst.imapmcp.store.secret;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** Seam: is the keyring CLI for the platform present? Tests substitute a fake. */
@FunctionalInterface
public interface BinaryProbe {

    boolean available(Platform platform);

    /** Probe against the real file system; the Linux PATH comes from the injected environment. */
    static BinaryProbe system(java.util.Map<String, String> env) {
        Map<String, String> environment = Map.copyOf(env);
        return platform -> switch (platform) {
            case LINUX -> onPath("secret-tool", environment.get("PATH"));
            case MACOS -> isExecutable(Path.of("/usr/bin/security"));
            case WINDOWS, OTHER -> false;
        };
    }

    private static boolean onPath(String name, String path) {
        if (path == null) {
            return false;
        }
        for (String dir : path.split(java.io.File.pathSeparator)) {
            if (!dir.isEmpty() && isExecutable(Path.of(dir, name))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isExecutable(Path p) {
        return Files.isRegularFile(p) && Files.isExecutable(p);
    }
}
