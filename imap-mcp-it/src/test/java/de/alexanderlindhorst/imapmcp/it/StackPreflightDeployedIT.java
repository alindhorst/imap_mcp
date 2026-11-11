package de.alexanderlindhorst.imapmcp.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.dockerjava.api.model.Network;
import de.alexanderlindhorst.imapmcp.core.net.AddressBlocklist;
import java.net.InetAddress;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * What must hold before any flow runs on the deployed stack (#427): an isolated internal network inside the test
 * subnet that the production SSRF policy admits, a copy that really is the project directory, a forward proxy that
 * refuses everything but CONNECT to *.imap-mcp.test:8443, and no secret in any captured output.
 */
class StackPreflightDeployedIT {

    private static DeployedStack stack;

    @BeforeAll
    static void start() {
        stack = DeployedStack.instance();
    }

    @Test
    void backendNetworkIsInternalWithoutIpv6() {
        Network net = stack.backendNetwork();
        assertThat(net.getInternal()).isTrue();
        assertThat(net.getEnableIPv6()).isFalse();
    }

    @Test
    void everyBackendContainerIsInsideTheSubnetAndNotBlockedByTheProductionBlocklist() throws Exception {
        AddressBlocklist blocklist = new AddressBlocklist(List.of());
        List<String> ips = stack.backendContainerIps();
        assertThat(ips).hasSizeGreaterThanOrEqualTo(5);
        de.alexanderlindhorst.imapmcp.core.net.Cidr subnet =
                de.alexanderlindhorst.imapmcp.core.net.Cidr.parse(stack.backendSubnet());
        for (String ip : ips) {
            InetAddress address = InetAddress.getByName(ip);
            assertThat(subnet.contains(address)).as("inside subnet: " + ip).isTrue();
            assertThat(blocklist.isBlocked(address)).as("not blocked: " + ip).isFalse();
        }
    }

    @Test
    void theProjectDirectoryIsTheCopyAndTheSourceTreeStaysUntouched() throws Exception {
        Map<String, String> env = DeployedSecretScan.secrets(stack.runDir().resolve(".env"));
        // the container got the value from the copy's .env (compared as a boolean, never printed)
        assertThat(stack.containerEnv("app", "HANDLE_KEY_1").equals(env.get("HANDLE_KEY_1"))).isTrue();
        assertThat(stack.runDir()).isNotEqualTo(stack.sourceDir());
        assertThat(Files.exists(stack.sourceDir().resolve(".env"))).isFalse();
        assertThat(DeployedRunCopy.snapshot(stack.sourceDir())).isEqualTo(stack.sourceSnapshotAtStart());
    }

    @Test
    void theProxyAllowsOnlyConnectToTheTestDomainOnPort8443() throws Exception {
        assertThat(stack.proxyConnect("mail.imap-mcp.test:8443")).contains(" 200");
        assertThat(stack.proxyConnect("idp.imap-mcp.test:8443")).contains(" 200");
        assertThat(stack.proxyConnect("example.com:8443")).contains(" 403");
        assertThat(stack.proxyConnect("mail.imap-mcp.test:22")).contains(" 403");
        assertThat(stack.proxyConnect("192.31.196.2:8443")).contains(" 403");
    }

    @Test
    void imapHostResolvesToOneIpv4InsideTheSubnetWithoutAaaaAndIsNotBlocked() throws Exception {
        DeployedCompose.ExecResult r = stack.exec("app", List.of(), "getent", "ahosts", DeployedDovecot.HOST);
        assertThat(r.exitCode()).isZero();
        java.util.Set<String> addresses = new java.util.TreeSet<>();
        for (String line : r.stdout().split("\\R")) {
            String[] cols = line.trim().split("\\s+");
            if (cols.length >= 1 && !cols[0].isEmpty()) {
                addresses.add(cols[0]);
            }
        }
        assertThat(addresses).hasSize(1);
        String ip = addresses.iterator().next();
        assertThat(ip).as("IPv4 only, no AAAA").matches("\\d+\\.\\d+\\.\\d+\\.\\d+");
        InetAddress address = InetAddress.getByName(ip);
        assertThat(de.alexanderlindhorst.imapmcp.core.net.Cidr.parse(stack.backendSubnet()).contains(address)).isTrue();
        assertThat(new AddressBlocklist(List.of()).isBlocked(address)).isFalse();
    }

    @Test
    void dovecotPresentsTheLeafForTheImapHostOn993ChainedToTheTestCa() throws Exception {
        DeployedCompose.ExecResult r = stack.exec("app", List.of(), "sh", "-c",
                "echo | openssl s_client -connect " + DeployedDovecot.HOST + ":993 -servername " + DeployedDovecot.HOST
                        + " -showcerts 2>/dev/null");
        assertThat(r.exitCode()).isZero();
        java.security.cert.CertificateFactory cf = java.security.cert.CertificateFactory.getInstance("X.509");
        List<java.security.cert.X509Certificate> chain = new java.util.ArrayList<>();
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("-----BEGIN CERTIFICATE-----.*?-----END CERTIFICATE-----", java.util.regex.Pattern.DOTALL)
                .matcher(r.stdout());
        while (m.find()) {
            chain.add((java.security.cert.X509Certificate) cf.generateCertificate(
                    new java.io.ByteArrayInputStream(m.group().getBytes(java.nio.charset.StandardCharsets.US_ASCII))));
        }
        assertThat(chain).isNotEmpty();
        java.security.cert.X509Certificate leaf = chain.get(0);
        assertThat(leaf.getSubjectAlternativeNames().stream().map(e -> (Object) e.get(1)).toList())
                .containsExactly(DeployedDovecot.HOST);
        java.security.cert.X509Certificate root = (java.security.cert.X509Certificate) cf.generateCertificate(
                new java.io.ByteArrayInputStream(Files.readAllBytes(stack.runDir().resolve("local-ca/ca.crt"))));
        java.security.cert.PKIXParameters params = new java.security.cert.PKIXParameters(
                java.util.Set.of(new java.security.cert.TrustAnchor(root, null)));
        params.setRevocationEnabled(false);
        java.security.cert.CertPathValidator.getInstance("PKIX")
                .validate(cf.generateCertPath(List.of(leaf)), params);
    }

    @Test
    void thereIsNoLoginInTheDovecotLogBeforeAnyFlowStarts() {
        assertThat(stack.dovecotLoginsAtStart()).isZero();
    }

    /** Non-vacuity: the baseline counter must see a real login (the password travels in the exec env only). */
    @Test
    void theLoginCounterSeesARealLoginOfOneUser() throws Exception {
        DeployedDovecot.ImapUser user = stack.dovecotUsers().get(0);
        DeployedDovecot.ImapUser other = stack.dovecotUsers().get(1);
        int before = stack.dovecotLogins(user.name());
        int otherBefore = stack.dovecotLogins(other.name());
        DeployedCompose.ExecResult r = stack.exec("app",
                List.of("IMAP_U=" + user.name(), "IMAP_P=" + user.password().value()), "sh", "-c",
                "(printf 'a LOGIN %s %s\\r\\n' \"$IMAP_U\" \"$IMAP_P\"; sleep 2; printf 'b LOGOUT\\r\\n'; sleep 1)"
                        + " | openssl s_client -quiet -connect " + DeployedDovecot.HOST + ":993 -servername "
                        + DeployedDovecot.HOST + " 2>/dev/null");
        assertThat(r.stdout()).contains("a OK");
        assertThat(stack.dovecotLogins(user.name())).isEqualTo(before + 1);
        assertThat(stack.dovecotLogins(other.name())).isEqualTo(otherBefore);
    }

    @Test
    void everyUserHasOneUnreadSeedMessageWithTheCanarySubject() throws Exception {
        assertThat(stack.dovecotUsers()).hasSize(2);
        for (DeployedDovecot.ImapUser user : stack.dovecotUsers()) {
            DeployedCompose.ExecResult r = stack.exec("dovecot", List.of(), "doveadm", "fetch", "-u", user.name(),
                    "flags hdr.subject", "mailbox", "INBOX", "all");
            assertThat(r.exitCode()).isZero();
            assertThat(r.stdout()).contains("hdr.subject: " + stack.canarySubject()).doesNotContain("\\Seen");
            assertThat(r.stdout().split("hdr.subject:", -1)).hasSize(2);
        }
    }

    @Test
    void theBuildContextCarriesOnlyTheDockerfileAndTheJar() {
        assertThat(stack.buildContextEntries()).containsExactlyInAnyOrder("Dockerfile", "jar/imap-mcp.jar");
    }

    @Test
    void noGeneratedSecretAppearsInAnyCapturedOutput() throws Exception {
        stack.saveLogs();
        Map<String, String> canaries = new java.util.TreeMap<>(
                DeployedSecretScan.secrets(stack.runDir().resolve(".env")));
        assertThat(canaries).isNotEmpty();
        stack.dovecotUsers().forEach(u -> canaries.put("DOVECOT_PASSWORD_" + u.name(), u.password().value()));
        int i = 0;
        for (String line : DeployedSecretScan.pemLines(stack.runDir().resolve("local-ca/ca.key"))) {
            canaries.put("CA_KEY_LINE_" + i++, line);
        }
        StringBuilder scanned = new StringBuilder(stack.capturedOutput());
        try (var logs = Files.list(stack.runDir().resolve("logs"))) {
            for (var f : (Iterable<java.nio.file.Path>) logs::iterator) {
                scanned.append(Files.readString(f));
            }
        }
        for (String file : List.of("it-compose.override.yaml", "squid.conf")) {
            scanned.append(Files.readString(stack.runDir().resolve(file)));
        }
        assertThat(DeployedSecretScan.leaks(scanned.toString(), canaries)).isEmpty();
    }
}
