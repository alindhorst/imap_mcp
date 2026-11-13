package de.alexanderlindhorst.imapmcp.core.linking;

import de.alexanderlindhorst.imapmcp.core.tenancy.TenantId;
import java.util.List;
import java.util.Optional;

/**
 * The upstream OAuth linking flow of {@code http-public} (SPEC 5.3), beside {@link AccountLinker}. {@code start}
 * takes the tenant of the web session; {@code callback} takes the tenant from the pending row that the session's
 * {@code start} wrote, never from the request.
 */
public interface UpstreamLinker {

    /** The provider to redirect to ({@code location}) and the state for the binding cookie, when {@code outcome} is OK. */
    record Started(UpstreamOutcome outcome, String location, String state) {

        @Override
        public String toString() {
            return "Started[" + outcome + "]";
        }
    }

    /** {@code relink}: the flow finished as a re-link (for the page text). */
    record Completed(UpstreamOutcome outcome, boolean relink) {
    }

    List<UpstreamProviderInfo> providers();

    Optional<UpstreamProviderInfo> provider(String id);

    /**
     * {@code relinkHandle} (the {@code acc_} handle, looked up within the tenant) makes it a re-link: provider and
     * address are the account's, and {@code label}, {@code address} and {@code readOnly} are ignored.
     */
    Started start(TenantId tenant, String providerId, String label, String address, boolean readOnly,
            String relinkHandle, String requestId);

    /**
     * Completes the flow. {@code state} is consumed first; {@code cookie} is the value of {@code __Host-upstream} and
     * {@code pathProviderId} the id in the callback path, both must match the pending row. {@code code} and
     * {@code error} are the query parameters as received (either may be null).
     */
    Completed callback(String state, String cookie, String pathProviderId, String code, String error);
}
