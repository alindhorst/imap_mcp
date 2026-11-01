package de.alexanderlindhorst.imapmcp.store.secret;

/** Seam: is the keyring CLI for the platform present? Tests substitute a fake. */
@FunctionalInterface
public interface BinaryProbe {

    boolean available(Platform platform);

    /** Probe against the real file system; the Linux PATH comes from the injected environment. */
    static BinaryProbe system(java.util.Map<String, String> env) {
        throw new UnsupportedOperationException();
    }
}
