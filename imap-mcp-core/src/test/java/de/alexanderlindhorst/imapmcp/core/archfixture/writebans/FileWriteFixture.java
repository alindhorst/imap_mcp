package de.alexanderlindhorst.imapmcp.core.archfixture.writebans;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Violates the file-write rule: creates, writes, links, moves and deletes files. Excluded from the real scan. */
public class FileWriteFixture {
    void write(Path p) throws IOException {
        Files.newOutputStream(p).close();
        Files.write(p, new byte[0]);
        Files.writeString(p, "x");
        Files.createFile(p);
        Files.createLink(p, p);
        Files.createSymbolicLink(p, p);
        Files.createTempFile("a", "b");
        Files.createDirectory(p);
        Files.createDirectories(p);
        Files.move(p, p);
        Files.copy(p, p);
        Files.delete(p);
        Files.deleteIfExists(p);
        Files.newByteChannel(p, StandardOpenOption.WRITE).close();
        FileChannel.open(p, StandardOpenOption.WRITE).close();
        new FileOutputStream("x").close();
    }
}
