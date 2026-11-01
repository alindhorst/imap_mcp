package de.alexanderlindhorst.imapmcp.core.search;

import static org.assertj.core.api.Assertions.assertThat;

import de.alexanderlindhorst.imapmcp.core.error.ToolError;
import de.alexanderlindhorst.imapmcp.core.search.SearchArguments.Parsed;
import de.alexanderlindhorst.imapmcp.core.tool.InputValidator;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Schema (through the generic validator) plus the constraints SearchArguments adds. */
class SearchInputTest {

    private static final InputValidator VALIDATOR = new InputValidator(SearchSchema.inputSchema());

    private static Map<String, Object> args(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("account", "acc_abc");
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    /** Full pipeline as #27 runs it: validator, then parse. Returns the error map's details/code view. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> error(Map<String, Object> a) {
        var r = VALIDATOR.validate(a, "rid");
        ToolError e = r.valid() ? SearchArguments.parse(r.args(), "rid").error() : r.error();
        assertThat(e).as("expected an error for %s", a.keySet()).isNotNull();
        assertThat(e.code().name()).isEqualTo("INVALID_ARGUMENT");
        return (Map<String, Object>) e.toMap().get("error");
    }

    private static void assertField(Map<String, Object> a, String field, String reason) {
        Map<String, Object> err = error(a);
        @SuppressWarnings("unchecked")
        Map<String, Object> d = (Map<String, Object>) err.get("details");
        assertThat(d).containsEntry("field", field).containsEntry("reason", reason);
    }

    private static Parsed ok(Map<String, Object> a) {
        var r = VALIDATOR.validate(a, "rid");
        assertThat(r.valid()).isTrue();
        Parsed p = SearchArguments.parse(r.args(), "rid");
        assertThat(p.error()).isNull();
        return p;
    }

    // ---- AC4 ----

    @Test
    void elevenKeywordsRejected() {
        List<String> kws = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            kws.add("k" + i);
        }
        assertField(args("keywords", kws), "keywords", "out_of_range");
    }

    @Test
    void keywordAtomRules() {
        assertField(args("keywords", List.of("\\Seen")), "keywords[0]", "format");
        for (String bad : new String[] {"a b", "a(b", "a)b", "a{b", "a%b", "a*b", "a\"b", "a]b", "a\\b", "a\u0001b",
                "ä", "a\u007fb"}) {
            assertField(args("keywords", List.of(bad)), "keywords[0]", "format");
        }
        assertField(args("keywords", List.of("")), "keywords[0]", "out_of_range");
        assertField(args("keywords", List.of("x".repeat(65))), "keywords[0]", "out_of_range");
        assertThat(ok(args("keywords", List.of("$Forwarded", "Project-X_1", "x".repeat(64)))).criteria().keywords())
                .hasSize(3);
    }

    @Test
    void pageSizeBounds() {
        assertField(args("page_size", 0), "page_size", "out_of_range");
        assertField(args("page_size", 101), "page_size", "out_of_range");
        assertThat(ok(args("page_size", 100)).pageSize()).isEqualTo(100);
        assertThat(ok(args()).pageSize()).isEqualTo(25);
    }

    @Test
    void sinceNeedsOffset() {
        assertField(args("since", "2026-03-01T10:00:00"), "since", "format");
        assertField(args("since", "2026-03-01"), "since", "format");
        Parsed p = ok(args("since", "2026-03-01T12:00:00+02:00"));
        assertThat(p.criteria().since()).isEqualTo(Instant.parse("2026-03-01T10:00:00Z"));
    }

    @Test
    void folderAndFolderRoleAreMutuallyExclusive() {
        assertField(args("folder", "INBOX", "folder_role", "inbox"), "folder_role", "conflict");
        assertThat(ok(args("folder_role", "sent")).folderRole()).isEqualTo("sent");
        assertThat(ok(args("folder", "Foo")).folder()).isEqualTo("Foo");
        assertField(args("folder_role", "bogus"), "folder_role", "out_of_range");
    }

    @Test
    void unknownPropertyAndMissingAccount() {
        assertField(args("bogus", 1), "bogus", "unknown_property");
        Map<String, Object> noAccount = new LinkedHashMap<>();
        assertField(noAccount, "account", "required");
    }

    @Test
    void stringLengthBounds() {
        for (String f : new String[] {"from", "to", "subject", "text"}) {
            assertField(args(f, "x".repeat(257)), f, "out_of_range");
            assertField(args(f, ""), f, "out_of_range");
        }
        assertField(args("gmail_raw", "x".repeat(1025)), "gmail_raw", "out_of_range");
        assertField(args("cursor", "x".repeat(257)), "cursor", "out_of_range");
    }

    // ---- AC5 ----

    @Test
    void dateFromAfterDateToRejected() {
        assertField(args("date_from", "2026-03-06", "date_to", "2026-03-05"), "date_from", "out_of_range");
        Parsed p = ok(args("date_from", "2026-03-05", "date_to", "2026-03-05"));
        assertThat(p.criteria().dateFrom()).isEqualTo(LocalDate.of(2026, 3, 5));
        assertField(args("date_from", "2026-13-05"), "date_from", "format");
    }

    @Test
    void whitespaceOnlyTextFieldsRejected() {
        for (String f : new String[] {"from", "to", "subject", "text"}) {
            assertField(args(f, "  \t "), f, "format");
        }
    }

    @Test
    void parsedFieldsAreStrippedAndMapped() {
        Parsed p = ok(args("from", " a@b ", "to", "t", "subject", "s", "text", "x", "seen", false, "flagged", true,
                "answered", false, "draft", true, "gmail_raw", "has:attachment", "cursor", "c1.x"));
        SearchCriteria c = p.criteria();
        assertThat(c.from()).isEqualTo("a@b");
        assertThat(c.seen()).isFalse();
        assertThat(c.flagged()).isTrue();
        assertThat(c.answered()).isFalse();
        assertThat(c.draft()).isTrue();
        assertThat(c.gmailRaw()).isEqualTo("has:attachment");
        assertThat(p.cursor()).isEqualTo("c1.x");
        assertThat(ok(args()).criteria()).isEqualTo(SearchCriteria.empty());
    }

    // ---- folder limits (lead comment, AC6) ----

    @Test
    void folderByteLimit() {
        assertThat(ok(args("folder", "€".repeat(333))).folder()).hasSize(333);
        assertField(args("folder", "€".repeat(334)), "folder", "out_of_range");
        assertField(args("folder", "a".repeat(1001)), "folder", "out_of_range");
        assertThat(ok(args("folder", "a".repeat(1000))).folder()).hasSize(1000);
        Map<String, Object> err = error(args("folder", "€".repeat(334)));
        @SuppressWarnings("unchecked")
        Map<String, Object> d = (Map<String, Object>) err.get("details");
        assertThat(d).containsEntry("max", 1000);
        assertThat(err.toString()).doesNotContain("€");
        // same payload for both over-limit variants
        assertThat(error(args("folder", "a".repeat(1001)))).isEqualTo(err);
    }

    @Test
    void unpairedSurrogateIsFormatError() {
        assertField(args("folder", "QZ\ud800cd"), "folder", "format");
        assertField(args("folder", "ab\udc00"), "folder", "format");
        assertThat(error(args("folder", "QZ\ud800cd")).toString()).doesNotContain("QZ");
        assertThat(ok(args("folder", "a😀")).folder()).hasSize(3);
    }
}
