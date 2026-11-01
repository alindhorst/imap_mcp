package de.alexanderlindhorst.imapmcp.transport.stdio;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/** In-memory MCP client side of the protocol streams: the server reads {@link #serverIn}, writes {@link #serverOut}. */
final class StdioClientHarness {

    static final McpJsonMapper MAPPER = McpJsonDefaults.getMapper();
    private static final byte[] EOF = new byte[0];

    private final BlockingQueue<byte[]> queue = new LinkedBlockingQueue<>();
    private final ByteArrayOutputStream collected = new ByteArrayOutputStream();
    private int nextId = 1;

    final InputStream serverIn = new InputStream() {
        private byte[] current = EOF;
        private int pos;
        private boolean eof;

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int n = read(one, 0, 1);
            return n < 0 ? -1 : one[0] & 0xff;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (eof) {
                return -1;
            }
            try {
                while (pos >= current.length) {
                    current = queue.take();
                    pos = 0;
                    if (current == EOF) {
                        eof = true;
                        return -1;
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted");
            }
            int n = Math.min(len, current.length - pos);
            System.arraycopy(current, pos, b, off, n);
            pos += n;
            return n;
        }
    };

    final OutputStream serverOut = new OutputStream() {
        @Override
        public void write(int b) {
            synchronized (collected) {
                collected.write(b);
            }
        }

        @Override
        public void write(byte[] b, int off, int len) {
            synchronized (collected) {
                collected.write(b, off, len);
            }
        }
    };

    ProtocolStreams streams() {
        return new ProtocolStreams(serverIn, serverOut);
    }

    void sendRaw(String line) {
        queue.add((line + "\n").getBytes(StandardCharsets.UTF_8));
    }

    /** Sends a request and returns its id. */
    int request(String method, Map<String, Object> params) throws IOException {
        int id = nextId++;
        sendRaw(MAPPER.writeValueAsString(Map.of("jsonrpc", "2.0", "id", id, "method", method, "params", params)));
        return id;
    }

    void notification(String method) throws IOException {
        sendRaw(MAPPER.writeValueAsString(Map.of("jsonrpc", "2.0", "method", method)));
    }

    void closeInput() {
        queue.add(EOF);
    }

    void initialize() throws Exception {
        int id = request("initialize", Map.of("protocolVersion", io.modelcontextprotocol.spec.ProtocolVersions.MCP_2025_11_25,
                "capabilities", Map.of(), "clientInfo", Map.of("name", "test", "version", "1")));
        awaitResponse(id);
        notification("notifications/initialized");
    }

    /** Output split into lines (complete lines only). */
    List<String> lines() {
        String all;
        synchronized (collected) {
            all = collected.toString(StandardCharsets.UTF_8);
        }
        return all.isEmpty() ? List.of() : all.lines().toList();
    }

    String rawOutput() {
        synchronized (collected) {
            return collected.toString(StandardCharsets.UTF_8);
        }
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> awaitResponse(int id) throws Exception {
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (System.nanoTime() < deadline) {
            for (String line : lines()) {
                Map<String, Object> msg = MAPPER.readValue(line, Map.class);
                if (msg.get("id") instanceof Number n && n.intValue() == id && !msg.containsKey("method")) {
                    return msg;
                }
            }
            Thread.sleep(10);
        }
        throw new AssertionError("no response for id " + id + " within 10 s");
    }
}
