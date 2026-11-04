package de.alexanderlindhorst.imapmcp.it;

import java.util.List;
import java.util.Optional;

/** Read smoke test against a real Microsoft 365 account (app password / basic auth IMAP only; no OAuth in v2). */
class M365ReadLiveSmoke extends AbstractReadSmoke {

    @Override
    Optional<SmokeEnv.Settings> settings() {
        return SmokeEnv.settings(SmokeEnv.Provider.M365, System.getenv());
    }

    @Override
    String skipReason() {
        return SmokeEnv.skipReason(SmokeEnv.Provider.M365, System.getenv()).orElse("");
    }

    @Override
    List<String> expectedRoles() {
        return List.of("sent", "drafts", "trash");
    }
}
