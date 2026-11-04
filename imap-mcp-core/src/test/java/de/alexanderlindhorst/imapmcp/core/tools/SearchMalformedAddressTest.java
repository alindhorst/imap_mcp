package de.alexanderlindhorst.imapmcp.core.tools;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** #269: a malformed address degrades its field; the message is never dropped from the page. */
class SearchMalformedAddressTest {

    SearchEnv env;

    @BeforeEach
    void setUp() {
        env = new SearchEnv();
    }

    @AfterEach
    void tearDown() {
        env.close();
    }

    private void deliverRaw(String from, String to, String subject) throws Exception {
        String raw = "From: " + from + "\r\nTo: " + to + "\r\nSubject: " + subject
                + "\r\nDate: Thu, 05 Mar 2026 10:00:00 +0000\r\nMessage-ID: <" + subject + "@x>\r\n\r\nbody\r\n";
        env.user.deliver(new MimeMessage(Session.getInstance(new java.util.Properties()),
                new ByteArrayInputStream(raw.getBytes(StandardCharsets.UTF_8))));
    }

    @SuppressWarnings("unchecked")
    @Test
    void domainlessAndUnparsableAddressesNeverDropTheMessage() throws Exception {
        deliverRaw("smoke-write", "searchuser@localhost", "from-domainless");
        deliverRaw("a@example.org", "smoke-write", "to-domainless");
        deliverRaw("", "searchuser@localhost", "from-empty");
        deliverRaw("<<<@@@>>>", "\"unterminated, <x", "unparsable");
        deliverRaw("ok@example.org", "searchuser@localhost", "normal");
        long unseenBefore = env.unseenCount();

        Map<String, Object> args = new LinkedHashMap<>();
        args.put("account", env.handleT1);
        Map<String, Object> out = env.ok(SearchEnv.T1, args);

        List<Map<String, Object>> messages = (List<Map<String, Object>>) out.get("messages");
        assertThat(out).containsEntry("total", 5).containsEntry("returned", 5);
        assertThat(messages).hasSize(5);
        assertThat(messages.stream().map(m -> m.get("handle")).distinct()).hasSize(5);
        Map<String, Object> first = (Map<String, Object>) messages.get(4).get("untrusted"); // oldest
        assertThat(first).containsKey("from"); // degraded, not dropped
        assertThat(first.get("to")).isInstanceOf(List.class);
        // PEEK: reading the page marked nothing as \Seen
        assertThat(env.unseenCount()).isEqualTo(unseenBefore);
    }
}
