package de.alexanderlindhorst.imapmcp.it;

import java.util.List;
import java.util.Optional;

/** Read smoke test against a real Gmail account; see {@link SmokeEnv} for the variables. */
class GmailReadLiveSmoke extends AbstractReadSmoke {

    @Override
    Optional<SmokeEnv.Settings> settings() {
        return SmokeEnv.settings(SmokeEnv.Provider.GMAIL, System.getenv());
    }

    @Override
    String skipReason() {
        return SmokeEnv.skipReason(SmokeEnv.Provider.GMAIL, System.getenv()).orElse("");
    }

    @Override
    List<String> expectedRoles() {
        return List.of("sent", "drafts", "trash", "junk", "all");
    }

    @Override
    boolean expectGmail() {
        return true;
    }
}
