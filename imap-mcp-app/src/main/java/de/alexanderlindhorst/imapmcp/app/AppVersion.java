package de.alexanderlindhorst.imapmcp.app;

import io.modelcontextprotocol.spec.McpSchema;

/** Server name and version announced by both launchers. */
final class AppVersion {

    static final String SERVER_NAME = "imap-mcp";

    private AppVersion() {
    }

    /** The jar's {@code Implementation-Version}; "dev" when run from classes. */
    static String version() {
        String v = AppVersion.class.getPackage().getImplementationVersion();
        return v != null ? v : "dev";
    }

    static McpSchema.Implementation serverInfo() {
        return new McpSchema.Implementation(SERVER_NAME, version());
    }
}
