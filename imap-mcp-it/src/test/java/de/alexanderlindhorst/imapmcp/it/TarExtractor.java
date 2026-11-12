package de.alexanderlindhorst.imapmcp.it;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;

/** Defensive extraction of a tar stream from a container (stub, red). */
final class TarExtractor {

    private TarExtractor() {
    }

    static int extract(InputStream tar, Path target, long perFileCap, long totalCap) throws IOException {
        throw new UnsupportedOperationException();
    }
}
