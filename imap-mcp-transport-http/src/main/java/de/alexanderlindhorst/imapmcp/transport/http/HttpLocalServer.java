package de.alexanderlindhorst.imapmcp.transport.http;

import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import java.util.function.Function;
import io.modelcontextprotocol.common.McpTransportContext;

public final class HttpLocalServer {

    public static final class Builder {
        public Builder port(int p) { return this; }
        public Builder bind(String b) { return this; }
        public Builder allowNonLoopbackBind(boolean b) { return this; }
        public Builder tool(McpStatelessServerFeatures.SyncToolSpecification t) { return this; }
        public Builder contextExtractor(Function<HttpServletRequest, McpTransportContext> f) { return this; }
        public Builder hostFilter(Filter f) { return this; }
        public Builder originFilter(Filter f) { return this; }
        public Builder bearerFilter(Filter f) { return this; }
        public Builder healthServlet(HttpServlet s) { return this; }
        public HttpLocalServer build() { return new HttpLocalServer(); }
    }

    public static Builder builder() { return new Builder(); }

    public void start() { throw new UnsupportedOperationException(); }
    public void stop() { throw new UnsupportedOperationException(); }
    public int port() { return 0; }
    public String host() { return null; }
}
