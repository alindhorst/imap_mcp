package de.alexanderlindhorst.imapmcp.core.tools.write;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import org.eclipse.angus.mail.iap.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CopyUidParserTest {

    @Test
    void parsesUntaggedOkOfMove() {
        Optional<CopyUidParser.CopyUid> r = CopyUidParser.parse(new Response[] {
                new Response("* OK [COPYUID 38505 304 3956] Moved"),
                new Response("* 3 EXPUNGE"),
                new Response("A1 OK Move completed")});
        assertThat(r).isPresent();
        assertThat(r.get().uidValidity()).isEqualTo(38505L);
        assertThat(r.get().sourceUids()).containsExactly(304L);
        assertThat(r.get().destinationUids()).containsExactly(3956L);
    }

    @Test
    void parsesTaggedOkOfCopy() {
        Optional<CopyUidParser.CopyUid> r = CopyUidParser.parse(new Response[] {
                new Response("A2 OK [COPYUID 7 10:11 20:21] Copy completed")});
        assertThat(r).isPresent();
        assertThat(r.get().sourceUids()).containsExactly(10L, 11L);
        assertThat(r.get().destinationUids()).containsExactly(20L, 21L);
    }

    @Test
    void expandsRangesAndPairsInResponseOrder() {
        Optional<CopyUidParser.CopyUid> r = CopyUidParser.parse(new Response[] {
                new Response("* OK [COPYUID 4294967295 9,3:5 200,100:102] ok")});
        assertThat(r).isPresent();
        assertThat(r.get().uidValidity()).isEqualTo(4294967295L);
        assertThat(r.get().sourceUids()).containsExactly(9L, 3L, 4L, 5L);
        assertThat(r.get().destinationUids()).containsExactly(200L, 100L, 101L, 102L);
        assertThat(r.get().destinationOf(4L)).hasValue(101L);
        assertThat(r.get().destinationOf(9L)).hasValue(200L);
        assertThat(r.get().destinationOf(77L)).isEmpty();
    }

    @Test
    void reversedRangeExpandsDescending() {
        CopyUidParser.CopyUid c = CopyUidParser.parse(new Response[] {
                new Response("A3 OK [COPYUID 7 5:3 12:10] ok")}).orElseThrow();
        assertThat(c.sourceUids()).containsExactly(5L, 4L, 3L);
        assertThat(c.destinationUids()).containsExactly(12L, 11L, 10L);
    }

    @Test
    void absentCopyUidIsEmptyNotAnException() {
        assertThat(CopyUidParser.parse(new Response[] {
                new Response("* OK [UIDNOTSTICKY] no"), new Response("A1 OK Done")})).isEmpty();
        assertThat(CopyUidParser.parse(new Response[0])).isEmpty();
        assertThat(CopyUidParser.parse((Response[]) null)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "* OK [COPYUID 7 1:3 10:11] unequal sizes",
            "* OK [COPYUID 7 1:3] missing dst",
            "* OK [COPYUID 7 a 1] not numeric",
            "* OK [COPYUID x 1 1] bad validity",
            "* OK [COPYUID 0 1 1] zero validity",
            "* OK [COPYUID 7 0 1] zero uid",
            "* OK [COPYUID 7 1:* 1:2] star",
            "* OK [COPYUID 7 1,, 1,2] empty element",
            "* OK [COPYUID 7 1:4294967295 1:4294967295] too large",
            "* OK [COPYUID 7 1 1 extra] trailing",
            "* OK [COPYUID 7 1 1 no closing bracket"})
    void malformedIsNoCopyUid(String line) {
        assertThat(CopyUidParser.parse(new Response[] {new Response(line)})).isEmpty();
    }

    @Test
    void onlyOkResponsesCount() {
        assertThat(CopyUidParser.parse(new Response[] {new Response("A1 NO [COPYUID 7 1 2] x")})).isEmpty();
        assertThat(CopyUidParser.parse(new Response[] {new Response("* BYE [COPYUID 7 1 2] x")})).isEmpty();
    }

    @Test
    void firstValidCopyUidWins() {
        CopyUidParser.CopyUid c = CopyUidParser.parse(new Response[] {
                new Response("* OK [COPYUID 7 1:3 10:11] bad"),
                new Response("* OK [COPYUID 8 1 5] good"),
                new Response("A1 OK [COPYUID 9 2 6] later")}).orElseThrow();
        assertThat(c.uidValidity()).isEqualTo(8L);
        assertThat(List.copyOf(c.destinationUids())).containsExactly(5L);
    }
}
