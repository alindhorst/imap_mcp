package de.alexanderlindhorst.imapmcp.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/** The failsafe wiring of #427: the deployed ITs run in their own execution of the default verify, not in the other. */
class DeployedFailsafeConfigTest {

    private static Document pom;

    @BeforeAll
    static void parse() throws Exception {
        pom = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(Files.newInputStream(Path.of("pom.xml")));
    }

    private static String text(String xpath) throws Exception {
        return (String) XPathFactory.newInstance().newXPath().evaluate(xpath, pom, XPathConstants.STRING);
    }

    private static final String FAILSAFE = "//plugin[artifactId='maven-failsafe-plugin']";

    @Test
    void theDefaultExecutionExcludesTheDeployedITs() throws Exception {
        assertThat(text(FAILSAFE + "/executions/execution[id='default']/configuration/excludes/exclude"))
                .isEqualTo("**/*DeployedIT.java");
    }

    @Test
    void aSeparateExecutionRunsOnlyTheDeployedITsWithItsOwnSummary() throws Exception {
        String ex = FAILSAFE + "/executions/execution[id='deployed-it']";
        assertThat(text(ex + "/configuration/includes/include")).isEqualTo("**/*DeployedIT.java");
        assertThat(text(ex + "/configuration/summaryFile")).contains("failsafe-summary-deployed.xml");
        NodeList goals = (NodeList) XPathFactory.newInstance().newXPath()
                .evaluate(ex + "/goals/goal", pom, XPathConstants.NODESET);
        java.util.List<String> names = new java.util.ArrayList<>();
        for (int i = 0; i < goals.getLength(); i++) {
            Node n = goals.item(i);
            names.add(n.getTextContent().strip());
        }
        assertThat(names).containsExactlyInAnyOrder("integration-test", "verify");
    }

    @Test
    void theDeployedExecutionIsNotParallelAndHasA15MinuteTimeout() throws Exception {
        String conf = FAILSAFE + "/executions/execution[id='deployed-it']/configuration";
        assertThat(text(conf + "/forkCount")).isEqualTo("1");
        assertThat(text(conf + "/reuseForks")).isEqualTo("true");
        assertThat(text(conf + "/properties/configurationParameters"))
                .contains("junit.jupiter.execution.parallel.enabled=false")
                .contains("junit.jupiter.execution.timeout.lifecycle.method.default=15m")
                .contains("junit.jupiter.execution.timeout.testable.method.default=15m");
    }
}
