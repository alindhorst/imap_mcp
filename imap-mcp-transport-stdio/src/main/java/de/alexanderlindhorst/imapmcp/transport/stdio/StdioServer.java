package de.alexanderlindhorst.imapmcp.transport.stdio;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;

/**
 * MCP server on the captured protocol streams (SPEC 2, 5.5, 5.6). Knows nothing about IMAP: it serves
 * the tool specifications it is given.
 *
 * <p>The SDK's stdio executors are non-daemon threads, so the process only ends if the server is closed
 * explicitly: {@link #awaitTermination()} blocks until stdin reaches EOF and then closes the server.
 *
 * <p>The application must set {@value #VIRTUAL_THREADS_PROPERTY}=true before Reactor's {@code Schedulers}
 * class is initialised (JVM flag or first statement of {@code main}); {@link #start} asserts it.
 * Tool calls then run on virtual threads and are not serialised on the single stdio inbound thread.
 */
public final class StdioServer {

    public static final String VIRTUAL_THREADS_PROPERTY = "reactor.schedulers.defaultBoundedElasticOnVirtualThreads";

    /** Session request timeout: at least the maximum {@code tools.deadline} (10 min). */
    static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(11);

    private final McpSyncServer server;
    private final CountDownLatch inputClosed;

    private StdioServer(McpSyncServer server, CountDownLatch inputClosed) {
        this.server = server;
        this.inputClosed = inputClosed;
    }

    public static StdioServer start(ProtocolStreams streams, McpJsonMapper mapper,
            List<McpServerFeatures.SyncToolSpecification> tools, McpSchema.Implementation serverInfo) {
        Objects.requireNonNull(streams, "streams");
        Objects.requireNonNull(mapper, "mapper");
        Objects.requireNonNull(tools, "tools");
        Objects.requireNonNull(serverInfo, "serverInfo");
        if (!"true".equals(System.getProperty(VIRTUAL_THREADS_PROPERTY))) {
            throw new IllegalStateException("System property " + VIRTUAL_THREADS_PROPERTY
                    + "=true must be set before Reactor initialises (e.g. -D" + VIRTUAL_THREADS_PROPERTY
                    + "=true on the java command line) so tool calls run on virtual threads");
        }
        CountDownLatch inputClosed = new CountDownLatch(1);
        var provider = new StdioServerTransportProvider(mapper, new EofSignalingInputStream(streams.in(), inputClosed),
                streams.out());
        McpSyncServer server = McpServer.sync(provider)
                .serverInfo(serverInfo)
                .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                // Input validation is ours (ValidatingToolHandler): the SDK's own error has another shape.
                .validateToolInputs(false)
                // Server-to-client requests (elicitation) wait for a person: the SDK's session-wide default of 10 s
                // must not end the wait, only the tool's own window does (SPEC 5.5). Above the 10 min maximum deadline.
                .requestTimeout(REQUEST_TIMEOUT)
                .tools(tools)
                .build();
        return new StdioServer(server, inputClosed);
    }

    /** Blocks until the protocol input reaches EOF (or fails), then closes the server gracefully. */
    public void awaitTermination() throws InterruptedException {
        inputClosed.await();
        server.closeGracefully();
    }

    /** Releases the latch when the wrapped stream reports EOF or fails. */
    private static final class EofSignalingInputStream extends FilterInputStream {
        private final CountDownLatch latch;

        EofSignalingInputStream(InputStream in, CountDownLatch latch) {
            super(in);
            this.latch = latch;
        }

        @Override
        public int read() throws IOException {
            try {
                int b = super.read();
                if (b < 0) {
                    latch.countDown();
                }
                return b;
            } catch (IOException | RuntimeException e) {
                latch.countDown();
                throw e;
            }
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            try {
                int n = super.read(b, off, len);
                if (n < 0) {
                    latch.countDown();
                }
                return n;
            } catch (IOException | RuntimeException e) {
                latch.countDown();
                throw e;
            }
        }
    }
}
