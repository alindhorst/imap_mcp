package de.alexanderlindhorst.imapmcp.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * The connector contract as an executable test (#435, SPEC 5.3 and 5.8 "Connector contract"): the connector
 * sequence of {@link ConnectorContractRun} against the deployed stack, compared with the fixture named by
 * {@code -Dimapmcp.connectorFixture} (default: the assumed one). Today the fixture is {@code source: assumed}; once
 * a recorded one replaces it the same test checks the stack against a real session. The display names carry the
 * fixture's source, so the gate log shows which evidence the run used.
 */
class ConnectorContractDeployedIT {

    @TestFactory
    Stream<DynamicTest> theConnectorSequenceConformsToTheFixtureAndReplaysItsStatuses() throws Exception {
        DeployedStack stack = DeployedStack.instance();
        ConnectorSessionFixture fixture = ConnectorContractRun.loadFixture(
                System.getProperty(ConnectorContractRun.PROPERTY));
        String source = "[fixture source: " + fixture.header().source() + "]";
        ConnectorContractRun.Result run = ConnectorContractRun.run(stack, new KeycloakAdmin(stack), fixture);
        String text = write(run.trace());
        // the trace is clean (no secret, see the last test); kept for comparison with a recording
        java.nio.file.Files.writeString(java.nio.file.Path.of("target", "connector-contract-trace.jsonl"), text);

        return Stream.of(
                DynamicTest.dynamicTest("conformance: order, paths, parameter names, values, headers " + source,
                        () -> assertThat(ConnectorConformance.firstDifference(fixture, run.trace()))
                                .as("the first differing request%s", originHint(run.trace())).isEmpty()),
                DynamicTest.dynamicTest("replay: every recorded status is what the stack answers " + source, () -> {
                    List<String> expected = fixture.requests().stream()
                            .map(r -> r.host() + " " + r.method() + " " + r.path() + " -> " + r.status()).toList();
                    List<String> actual = run.trace().requests().stream()
                            .map(r -> r.host() + " " + r.method() + " " + r.path() + " -> " + r.status()).toList();
                    assertThat(actual).as("statuses%s", originHint(run.trace())).isEqualTo(expected);
                }),
                DynamicTest.dynamicTest("the trace holds no secret of this run " + source, () -> {
                    assertThat(run.secrets()).isNotEmpty()
                            .allSatisfy(s -> assertThat(text).doesNotContain(s));
                    assertThat(text).doesNotContain("eyJ");
                }));
    }

    /** A 403 on the MCP endpoint is the Origin setting (H9), not the connector sequence. */
    private static String originHint(ConnectorSessionFixture trace) {
        boolean forbidden = trace.requests().stream().anyMatch(r -> r.host().equals("mcp") && r.status() == 403);
        return forbidden ? " (a 403 on /mcp: the Origin header taken from the fixture is refused by the Origin"
                + " setting, H9)" : "";
    }

    private static String write(ConnectorSessionFixture f) {
        java.io.StringWriter w = new java.io.StringWriter();
        try {
            f.write(w);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        return w.toString();
    }
}
