package de.alexanderlindhorst.imapmcp.core.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.alexanderlindhorst.imapmcp.core.tool.ElicitationChannel;
import de.alexanderlindhorst.imapmcp.core.tool.ElicitationChannel.Answer;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import reactor.core.Exceptions;

/** The only place that talks to the SDK exchange for elicitation (SPEC 5.5): capability check, timed interruptible wait. */
class McpElicitationChannelTest {

    private final McpSyncServerExchange exchange = mock(McpSyncServerExchange.class);
    private final Map<String, Object> schema = Map.of("type", "object", "properties", Map.of());

    private McpElicitationChannel channel() {
        return new McpElicitationChannel(exchange);
    }

    private void caps(McpSchema.ClientCapabilities c) {
        when(exchange.getClientCapabilities()).thenReturn(c);
    }

    // ---- capability: form mode required; empty object means form; URL alone does not count ----

    @Test
    void formModeCapabilityRules() {
        caps(null);
        assertThat(channel().supportsFormMode()).isFalse();
        caps(McpSchema.ClientCapabilities.builder().build());
        assertThat(channel().supportsFormMode()).isFalse();
        caps(McpSchema.ClientCapabilities.builder().elicitation(true, false).build());
        assertThat(channel().supportsFormMode()).isTrue();
        caps(McpSchema.ClientCapabilities.builder().elicitation(true, true).build());
        assertThat(channel().supportsFormMode()).isTrue();
        caps(McpSchema.ClientCapabilities.builder().elicitation(false, true).build());
        assertThat(channel().supportsFormMode()).isFalse();
        // "elicitation": {} declares neither explicitly: form mode
        caps(McpSchema.ClientCapabilities.builder()
                .elicitation(new McpSchema.ClientCapabilities.Elicitation(null, null)).build());
        assertThat(channel().supportsFormMode()).isTrue();
    }

    // ---- the request and the answer mapping ----

    @Test
    void sendsOneFormRequestWithMessageAndSchema() throws Exception {
        AtomicReference<McpSchema.ElicitRequest> sent = new AtomicReference<>();
        when(exchange.createElicitation(any())).thenAnswer(inv -> {
            sent.set(inv.getArgument(0));
            return new McpSchema.ElicitResult(McpSchema.ElicitResult.Action.ACCEPT, Map.of("allow_for_session", true));
        });
        Answer a = channel().ask("hello", schema, Duration.ofSeconds(5));
        assertThat(a).isEqualTo(new Answer.Accepted(Map.of("allow_for_session", true)));
        assertThat(sent.get()).isInstanceOf(McpSchema.ElicitFormRequest.class);
        var form = (McpSchema.ElicitFormRequest) sent.get();
        assertThat(form.message()).isEqualTo("hello");
        assertThat(form.requestedSchema()).isEqualTo(schema);
    }

    @Test
    void actionsMapToAnswers() throws Exception {
        when(exchange.createElicitation(any())).thenReturn(
                new McpSchema.ElicitResult(McpSchema.ElicitResult.Action.DECLINE, null),
                new McpSchema.ElicitResult(McpSchema.ElicitResult.Action.CANCEL, null),
                new McpSchema.ElicitResult(McpSchema.ElicitResult.Action.ACCEPT, null));
        assertThat(channel().ask("m", schema, Duration.ofSeconds(5))).isEqualTo(new Answer.Declined());
        assertThat(channel().ask("m", schema, Duration.ofSeconds(5))).isEqualTo(new Answer.Cancelled());
        assertThat(channel().ask("m", schema, Duration.ofSeconds(5))).isEqualTo(new Answer.Accepted(null));
    }

    @Test
    void errorAnswerAndSendFailureAreFailed() throws Exception {
        when(exchange.createElicitation(any())).thenThrow(new McpError(new McpSchema.JSONRPCResponse.JSONRPCError(-32000, "rpc error", null)))
                .thenThrow(new IllegalStateException("client declared no elicitation"));
        assertThat(channel().ask("m", schema, Duration.ofSeconds(5))).isEqualTo(new Answer.Failed());
        assertThat(channel().ask("m", schema, Duration.ofSeconds(5))).isEqualTo(new Answer.Failed());
    }

    // ---- the timed, interruptible wait ----

    @Test
    void noAnswerWithinTheWindowIsTimedOutAndTheHelperIsInterrupted() throws Exception {
        CountDownLatch interrupted = new CountDownLatch(1);
        when(exchange.createElicitation(any())).thenAnswer(inv -> {
            try {
                new CountDownLatch(1).await(); // blocks like the SDK's block()
            } catch (InterruptedException e) {
                interrupted.countDown();
                throw Exceptions.propagate(e);
            }
            return null;
        });
        long start = System.nanoTime();
        assertThat(channel().ask("m", schema, Duration.ofMillis(200))).isEqualTo(new Answer.TimedOut());
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(3));
        assertThat(interrupted.await(3, TimeUnit.SECONDS)).as("helper interrupted").isTrue();
    }

    @Test
    void workerInterruptEndsTheWaitWithInterruptedExceptionAndInterruptsTheHelper() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch helperInterrupted = new CountDownLatch(1);
        when(exchange.createElicitation(any())).thenAnswer(inv -> {
            entered.countDown();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException e) {
                helperInterrupted.countDown();
                throw Exceptions.propagate(e);
            }
            return null;
        });
        AtomicBoolean gotInterrupted = new AtomicBoolean();
        Thread worker = Thread.ofVirtual().start(() -> {
            try {
                channel().ask("m", schema, Duration.ofSeconds(60));
            } catch (InterruptedException e) {
                gotInterrupted.set(true);
            }
        });
        assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
        worker.interrupt();
        worker.join(3000);
        assertThat(gotInterrupted).isTrue();
        assertThat(helperInterrupted.await(3, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void anAnswerAfterTheWindowIsDiscarded() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        when(exchange.createElicitation(any())).thenAnswer(inv -> {
            release.await(); // ignores the interrupt, like a late answer racing the dispose
            return new McpSchema.ElicitResult(McpSchema.ElicitResult.Action.ACCEPT, null);
        });
        assertThat(channel().ask("m", schema, Duration.ofMillis(100))).isEqualTo(new Answer.TimedOut());
        release.countDown();
    }

    @Test
    void channelIsAnElicitationChannel() {
        assertThat(channel()).isInstanceOf(ElicitationChannel.class);
    }

    @Test
    void sessionIdIsTheExchangesSessionId() {
        when(exchange.sessionId()).thenReturn("sess-42");
        assertThat(channel().sessionId()).isEqualTo("sess-42");
    }

    @Test
    void channelsWithoutASessionReportTheStdioConstant() {
        ElicitationChannel plain = new ElicitationChannel() {
            @Override
            public boolean supportsFormMode() {
                return true;
            }

            @Override
            public Answer ask(String message, Map<String, Object> requestedSchema, Duration window) {
                return new Answer.Failed();
            }
        };
        assertThat(plain.sessionId()).isEqualTo(ElicitationChannel.STDIO_SESSION);
    }
}
