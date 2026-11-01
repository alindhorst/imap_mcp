package de.alexanderlindhorst.imapmcp.core.mime;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class HtmlToTextTest {

    // AC1: block structure
    @Test
    void paragraphsAreSeparatedByOneBlankLine() {
        assertThat(HtmlToText.convert("<p>A</p><p>B</p>")).isEqualTo("A\n\nB");
    }

    @Test
    void brIsOneNewlineAndDoubleBrIsBlankLine() {
        assertThat(HtmlToText.convert("A<br>B")).isEqualTo("A\nB");
        assertThat(HtmlToText.convert("A<br><br>B")).isEqualTo("A\n\nB");
    }

    @Test
    void divListItemsAndTableRowsAreLineBreaks() {
        assertThat(HtmlToText.convert("<div>A</div><div>B</div>")).isEqualTo("A\nB");
        assertThat(HtmlToText.convert("<ul><li>A</li><li>B</li></ul>")).isEqualTo("A\nB");
        assertThat(HtmlToText.convert("<table><tr><td>a</td><td>b</td></tr><tr><td>c</td></tr></table>"))
                .isEqualTo("a b\nc");
        assertThat(HtmlToText.convert("<h1>T</h1>x<blockquote>q</blockquote>y")).isEqualTo("T\n\nx\n\nq\n\ny");
    }

    @Test
    void neverMoreThanOneBlankLineInARow() {
        assertThat(HtmlToText.convert("<p>A</p><p></p><p>&nbsp;</p><br><br><br><br><p>B</p>"))
                .isEqualTo("A\n\nB");
    }

    @Test
    void whitespaceRunsCollapseAndEdgesAreTrimmed() {
        assertThat(HtmlToText.convert("  <p>  a \n\t b  <b>c</b> </p>\n\n  ")).isEqualTo("a b c");
        assertThat(HtmlToText.convert("a <br> b")).isEqualTo("a\nb");
    }

    @Test
    void commentsAreDroppedAndEntitiesDecoded() {
        assertThat(HtmlToText.convert("a<!-- SECRET -->b &amp; c &lt;d&gt; caf&eacute;"))
                .isEqualTo("ab & c <d> caf\u00e9");
    }

    @Test
    void imagesYieldAltTextOnly() {
        assertThat(HtmlToText.convert("x <img src=\"http://t.example/p.gif\" alt=\"logo\"> y")).isEqualTo("x logo y");
        assertThat(HtmlToText.convert("x<img src=\"http://t.example/p.gif\">y")).isEqualTo("xy");
    }

    @Test
    void nullAndEmpty() {
        assertThat(HtmlToText.convert(null)).isEmpty();
        assertThat(HtmlToText.convert("")).isEmpty();
    }

    @Test
    void controlAndBidiCharactersAreStrippedViaBodyText() {
        assertThat(HtmlToText.convert("a\u202eb\u0001c")).isEqualTo("abc");
    }

    // AC2: links
    @Test
    void httpsLinkIsRenderedWithUrl() {
        assertThat(HtmlToText.convert("<a href=\"https://x.example/\">click</a>"))
                .isEqualTo("click <https://x.example/>");
    }

    @Test
    void mailtoAndMixedCaseSchemeAreShown() {
        assertThat(HtmlToText.convert("<a href=\"mailto:a@x.example\">mail</a>")).isEqualTo("mail <mailto:a@x.example>");
        assertThat(HtmlToText.convert("<a href=\" HTTPS://x.example/\">go</a>")).isEqualTo("go <HTTPS://x.example/>");
    }

    @ParameterizedTest
    @ValueSource(strings = {"javascript:alert(1)", "JaVaScRiPt:alert(1)", "data:text/html,x", "file:///etc/passwd",
            "ftp://x.example/", "/relative", "https://x.example/a b", "https://x.example/>"})
    void otherLinksAreTextOnly(String href) {
        assertThat(HtmlToText.convert("<a href=\"" + href + "\">click</a>")).isEqualTo("click");
    }

    @Test
    void linkTextEqualToUrlIsOutputOnce() {
        assertThat(HtmlToText.convert("<a href=\"https://x.example/\">https://x.example/</a>"))
                .isEqualTo("https://x.example/");
    }

    @Test
    void linkWithoutTextShowsUrl() {
        assertThat(HtmlToText.convert("<a href=\"https://x.example/\"></a>")).isEqualTo("https://x.example/");
    }

    @Test
    void linkInsideTextKeepsSurroundings() {
        assertThat(HtmlToText.convert("see <a href=\"https://x.example/\">here</a>, ok")).isEqualTo(
                "see here <https://x.example/>, ok");
    }

    // AC3: hidden content
    @ParameterizedTest
    @ValueSource(strings = {
            "<div hidden>MARK</div>",
            "<div HIDDEN=\"\">MARK</div>",
            "<div style=\"display:none\">MARK</div>",
            "<div style=\"display : NONE\">MARK</div>",
            "<div style=\"color:red; DISPLAY:none !important\">MARK</div>",
            "<div style=\"visibility:hidden\">MARK</div>",
            "<div style=\"Visibility : Hidden\">MARK</div>",
            "<div style=\"font-size:0px\">MARK</div>",
            "<div style=\"font-size: 0pt\">MARK</div>",
            "<div style=\"font-size:0em\">MARK</div>",
            "<div style=\"font-size:0\">MARK</div>",
            "<div style=\"FONT-SIZE : 0.0px\">MARK</div>",
            "<div style=\"opacity:0\">MARK</div>",
            "<div style=\"opacity: 0.0\">MARK</div>",
            "<div hidden><p><span>MARK</span></p></div>",
            "<div style=\"display:none\"><p><b>MARK</b></p><a href=\"https://x.example/\">MARK</a></div>",
            "<style>MARK</style>",
            "<script>MARK</script>",
            "<head><title>MARK</title></head>",
            "<template>MARK</template>",
            "<noscript>MARK</noscript>",
            "<!-- MARK -->"})
    void hiddenContentIsDropped(String fragment) {
        assertThat(HtmlToText.convert("<html><body><p>before</p>" + fragment + "<p>after</p></body></html>"))
                .isEqualTo("before\n\nafter");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "<div style=\"font-size:0.5em\">MARK</div>",
            "<div style=\"font-size:10px\">MARK</div>",
            "<div style=\"opacity:0.5\">MARK</div>",
            "<div style=\"display:block\">MARK</div>",
            "<div style=\"visibility:visible\">MARK</div>",
            "<div style=\"line-font-size:0\">MARK</div>"})
    void visibleLookalikesAreKept(String fragment) {
        assertThat(HtmlToText.convert(fragment)).isEqualTo("MARK");
    }

    // AC4: determinism and size
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void fourMebibyteInputIsDeterministicAndFast() {
        StringBuilder sb = new StringBuilder("<html><body>");
        String chunk = "<p style=\"color:red\">Hello <b>world</b> <a href=\"https://x.example/a\">link</a></p>"
                + "<div style=\"display:none\">MARK</div>\n";
        while (sb.length() < 4 * 1024 * 1024) {
            sb.append(chunk);
        }
        sb.append("</body></html>");
        String html = sb.toString();
        String first = HtmlToText.convert(html);
        assertThat(first).startsWith("Hello world link <https://x.example/a>").doesNotContain("MARK");
        assertThat(HtmlToText.convert(html)).isEqualTo(first);
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void deeplyNestedInputDoesNotOverflowTheStack() {
        String html = "<div>".repeat(20_000) + "deep";
        assertThat(HtmlToText.convert(html)).contains("deep");
    }
}
