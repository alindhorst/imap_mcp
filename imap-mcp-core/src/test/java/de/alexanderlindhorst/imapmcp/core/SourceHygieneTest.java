package de.alexanderlindhorst.imapmcp.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Guards against "Trojan Source" style hazards: invisible, bidirectional-control and control characters must not
 * appear literally in any source under {@code src/main} or {@code src/test} of any module; write them as
 * {@code \}{@code uXXXX} escapes. Visible non-ASCII letters are tolerated but discouraged.
 * This file itself writes every forbidden code point as an escape only.
 */
class SourceHygieneTest {

    private static final Set<String> TEXT_EXTENSIONS =
            Set.of("java", "xml", "properties", "json", "txt", "md", "yml", "yaml", "eml", "html", "csv");

    static boolean forbidden(int cp) {
        return (cp >= 0x202A && cp <= 0x202E)
                || (cp >= 0x2066 && cp <= 0x2069)
                || (cp >= 0x200B && cp <= 0x200F)
                || cp == 0xFEFF || cp == 0x00A0 || cp == 0x2028 || cp == 0x2029
                || (cp < 0x20 && cp != '\t' && cp != '\n' && cp != '\r')
                || cp == 0x7F
                || (cp >= 0x80 && cp <= 0x9F);
    }

    static List<String> violations(Path file) throws IOException {
        List<String> out = new ArrayList<>();
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).codePoints().anyMatch(SourceHygieneTest::forbidden)) {
                out.add(file + ":" + (i + 1));
            }
        }
        return out;
    }

    private static Path repoRoot() {
        // surefire runs with the module directory as working directory
        return Path.of("").toAbsolutePath().getParent();
    }

    @Test
    void noInvisibleOrControlCharactersInAnySource() throws IOException {
        List<String> all = new ArrayList<>();
        try (Stream<Path> modules = Files.list(repoRoot())) {
            for (Path module : modules.toList()) {
                for (String tree : List.of("src/main", "src/test")) {
                    Path root = module.resolve(tree);
                    if (!Files.isDirectory(root)) {
                        continue;
                    }
                    try (Stream<Path> files = Files.walk(root)) {
                        for (Path f : files.filter(Files::isRegularFile).filter(SourceHygieneTest::isText).toList()) {
                            all.addAll(violations(f));
                        }
                    }
                }
            }
        }
        assertThat(all).as("files with literal invisible/bidi/control characters (use \\uXXXX escapes)").isEmpty();
    }

    private static boolean isText(Path f) {
        String n = f.getFileName().toString();
        int dot = n.lastIndexOf('.');
        return dot >= 0 && TEXT_EXTENSIONS.contains(n.substring(dot + 1));
    }

    @Test
    void guardDetectsEachForbiddenClass() {
        for (int cp : new int[] {0x202E, 0x202A, 0x2066, 0x2069, 0x200B, 0x200F, 0xFEFF, 0xA0, 0x01, 0x85, 0x2028,
                0x2029, 0x7F}) {
            assertThat(forbidden(cp)).as("U+%04X", cp).isTrue();
        }
        for (int cp : new int[] {'a', '\t', '\n', '\r', 0xE9, 0x1F600}) {
            assertThat(forbidden(cp)).as("U+%04X", cp).isFalse();
        }
    }
}
