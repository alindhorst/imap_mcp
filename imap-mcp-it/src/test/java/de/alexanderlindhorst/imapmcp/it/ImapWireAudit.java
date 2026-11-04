package de.alexanderlindhorst.imapmcp.it;

import java.util.List;
import java.util.Optional;

/** Wire-trace audit rules (SPEC 5.4, 5.8; #207). Stub: filled in after the red run. */
final class ImapWireAudit {

    private ImapWireAudit() {
    }

    static Optional<String> clientCommand(String traceLine) {
        return Optional.empty();
    }

    static List<String> violations(List<String> commands) {
        return List.of("stub");
    }

    static List<String> enableViolations(List<String> commands, boolean strict) {
        return List.of("stub");
    }

    static List<String> selectedFolders(List<String> commands, List<String> folders) {
        return List.of("stub");
    }

    static Optional<String> firstFolderCommand(List<String> commands) {
        return Optional.empty();
    }
}
