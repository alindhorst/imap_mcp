package de.alexanderlindhorst.imapmcp.core.download;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class EmbeddedMimeTest {

    @ParameterizedTest
    @ValueSource(strings = {"application/pdf", "image/png", "image/jpeg", "image/gif", "image/webp", "text/plain",
            "text/csv"})
    void listedTypesPassThrough(String type) {
        assertThat(EmbeddedMime.of(type)).isEqualTo(type);
    }

    @Test
    void caseWhitespaceAndParametersAreNormalized() {
        assertThat(EmbeddedMime.of("IMAGE/PNG")).isEqualTo("image/png");
        assertThat(EmbeddedMime.of("  text/plain ; charset=utf-8")).isEqualTo("text/plain");
        assertThat(EmbeddedMime.of("Application/PDF;name=\"x.pdf\"")).isEqualTo("application/pdf");
    }

    @ParameterizedTest
    @ValueSource(strings = {"text/html", "image/svg+xml", "application/javascript", "application/x-msdownload",
            "application/zip", "message/rfc822", "application/pdfx", "text/plain2", "text/", "/", "", " ",
            "application/pdf/evil", "image/png\r\nX: y", "text/plain\u0000", "text/csv; x", "*/*", "text/*"})
    void everythingElseIsOctetStream(String type) {
        if (type.equals("text/csv; x")) { // a parameter alone does not change the type
            assertThat(EmbeddedMime.of(type)).isEqualTo("text/csv");
            return;
        }
        assertThat(EmbeddedMime.of(type)).isEqualTo("application/octet-stream");
    }

    @Test
    void nullIsOctetStream() {
        assertThat(EmbeddedMime.of(null)).isEqualTo("application/octet-stream");
    }
}
