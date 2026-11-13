package de.alexanderlindhorst.imapmcp.store.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.alexanderlindhorst.imapmcp.core.account.Secret;
import de.alexanderlindhorst.imapmcp.core.audit.EventInfo;
import de.alexanderlindhorst.imapmcp.core.crypto.KeyRing;
import de.alexanderlindhorst.imapmcp.core.crypto.LogIds;
import de.alexanderlindhorst.imapmcp.core.metrics.MetricsRecorder;
import de.alexanderlindhorst.imapmcp.core.oauth.RefreshResult;
import de.alexanderlindhorst.imapmcp.core.tenancy.AccountId;
import de.alexanderlindhorst.imapmcp.core.tenancy.TenantId;
import de.alexanderlindhorst.imapmcp.store.crypto.CredentialCipher;
import de.alexanderlindhorst.imapmcp.store.crypto.CredentialDecryptException;
import de.alexanderlindhorst.imapmcp.store.crypto.LocalKeyWrapper;
import java.net.URI;
import java.security.SecureRandom;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class JdbcOAuthGrantStoreTest {

    private static final UUID T1 = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID T2 = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final String ACC = "acc-1";
    private static final String OLD_RT = "CANARY-OLD-REFRESH-31";
    private static final String NEW_RT = "CANARY-NEW-REFRESH-77";
    private static final String AT = "CANARY-ACCESS-12";
    private static final Instant EXP = Instant.parse("2026-11-13T13:00:00Z");

    private final CredentialCipher cipher = new CredentialCipher(
            new LocalKeyWrapper("k1", Map.of("k1", new byte[32])), new SecureRandom());
    private final LogIds logIds = new LogIds(new KeyRing(1, Map.of(1, new byte[32])));
    private final MetricsRecorder metrics = mock(MetricsRecorder.class);
    private final FakeDb db = new FakeDb();
    private final TokenProvider provider = new TokenProvider("generic", URI.create("https://t.example.test/token"), "cid",
            () -> new Secret(new char[] {'s'}));
    private TokenResponse answer = granted(null);
    private final List<String> clientCalls = new ArrayList<>();
    private final TokenEndpoint client = new TokenEndpoint() {
        @Override
        public TokenResponse refresh(TokenProvider p, char[] refreshToken) {
            clientCalls.add(new String(refreshToken));
            return answer;
        }

        @Override
        public TokenResponse exchangeCode(TokenProvider p, char[] code, char[] verifier, URI redirect) {
            throw new AssertionError();
        }
    };
    private Optional<TokenProvider> configured = Optional.of(provider);
    private final JdbcOAuthGrantStore store = new JdbcOAuthGrantStore(db, cipher, client, id -> configured, logIds,
            metrics);
    private ListAppender<ILoggingEvent> logs;
    private Logger root;

    private static TokenResponse granted(String rotated) {
        return new TokenResponse.Granted(new Secret(AT.toCharArray()), EXP,
                rotated == null ? null : new Secret(rotated.toCharArray()), "mail");
    }

    @BeforeEach
    void setUp() {
        db.putGrant(T1, ACC, "generic", 3, OLD_RT, cipher);
        root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        root.setLevel(Level.TRACE);
        logs = new ListAppender<>();
        logs.start();
        root.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        root.detachAppender(logs);
    }

    private RefreshResult refresh() {
        return store.refresh(new TenantId(T1.toString()), new AccountId(ACC));
    }

    @Test
    void rotationStoresTheNewVersionMovesThePointerAndDeletesTheOldOneInOneTransaction() {
        answer = granted(NEW_RT);

        RefreshResult res = refresh();

        assertThat(res).isInstanceOfSatisfying(RefreshResult.Refreshed.class, r -> {
            assertThat(new String(r.token().value().chars())).isEqualTo(AT);
            assertThat(r.token().expiresAt()).isEqualTo(EXP);
        });
        assertThat(clientCalls).containsExactly(OLD_RT);
        assertThat(db.committed).containsExactly("lock", "read:3", "insert:4", "point:4", "delete:3");
        assertThat(db.openRefresh(T1, ACC, 4, cipher)).isEqualTo(NEW_RT);
        assertThat(db.hasCredential(T1, ACC, 3)).isFalse();
        verify(metrics).oauthRefresh(new TenantId(T1.toString()), "ok");
    }

    @Test
    void withoutRotationOnlyRefreshedAtMoves() {
        refresh();

        assertThat(db.committed).containsExactly("lock", "read:3", "point:3");
        assertThat(db.hasCredential(T1, ACC, 3)).isTrue();
    }

    @Test
    void rotationNeverBumpsTheAccountRevision() {
        answer = granted(NEW_RT);

        refresh();

        assertThat(db.committed).noneMatch(s -> s.startsWith("relink") || s.startsWith("audit"));
    }

    @Test
    void invalidGrantFlipsToNeedsRelinkWithAuditEventInTheSameTransaction() {
        answer = new TokenResponse.InvalidGrant();

        assertThat(refresh()).isInstanceOf(RefreshResult.InvalidGrant.class);

        assertThat(db.committed).containsExactly("lock", "read:3", "relink", "audit:credential_revoked");
        assertThat(db.events).singleElement().satisfies(e -> {
            assertThat(e.type()).isEqualTo("credential_revoked");
            assertThat(e.tenant()).isEqualTo(new TenantId(T1.toString()));
            assertThat(e.account()).isEqualTo(new AccountId(ACC));
            assertThat(e.requestId()).isNotBlank();
        });
        assertThat(db.hasCredential(T1, ACC, 3)).isTrue();
        verify(metrics).oauthRefresh(new TenantId(T1.toString()), "invalid_grant");
    }

    @Test
    void invalidClientAndUnauthorizedClientNeverFlipTheAccount() {
        for (TokenResponse r : List.of(new TokenResponse.InvalidClient(), new TokenResponse.UnauthorizedClient())) {
            answer = r;
            db.committed.clear();

            assertThat(refresh()).isInstanceOf(RefreshResult.ClientRejected.class);

            assertThat(db.committed).noneMatch(s -> s.startsWith("relink") || s.startsWith("insert"));
        }
        verify(metrics, org.mockito.Mockito.times(2)).oauthRefresh(new TenantId(T1.toString()), "client_rejected");
    }

    @Test
    void unavailableMalformedAndRefusedAreUnavailableWithoutWrites() {
        answer = new TokenResponse.Unavailable(Duration.ofSeconds(9));
        assertThat(refresh()).isEqualTo(new RefreshResult.Unavailable(Duration.ofSeconds(9)));

        answer = new TokenResponse.Malformed();
        assertThat(refresh()).isEqualTo(new RefreshResult.Unavailable(null));

        answer = new TokenResponse.Refused();
        assertThat(refresh()).isEqualTo(new RefreshResult.Unavailable(null));

        assertThat(db.committed).noneMatch(s -> s.startsWith("relink") || s.startsWith("insert")
                || s.startsWith("point") || s.startsWith("delete"));
        verify(metrics, org.mockito.Mockito.times(3)).oauthRefresh(new TenantId(T1.toString()), "unavailable");
    }

    @Test
    void lockTimeoutIsReportedAndTheEndpointIsNeverCalled() {
        db.lockFailure = new SQLException("lock", "55P03");

        assertThat(refresh()).isInstanceOf(RefreshResult.LockTimeout.class);

        assertThat(clientCalls).isEmpty();
        verify(metrics).oauthRefresh(new TenantId(T1.toString()), "lock_timeout");
    }

    @Test
    void otherSqlFailuresBeforeTheExchangeAreInternalErrors() {
        db.lockFailure = new SQLException("boom", "08006");

        assertThatThrownBy(this::refresh).isInstanceOf(IllegalStateException.class);
        assertThat(clientCalls).isEmpty();
    }

    @Test
    void commitFailureAfterRotationCountsALostGrantAndLogsOnlyHashedIds() {
        answer = granted(NEW_RT);
        db.commitFailure = new SQLException("commit", "08006");

        assertThatThrownBy(this::refresh).isInstanceOf(IllegalStateException.class);

        verify(metrics).oauthGrantLost(new TenantId(T1.toString()));
        String all = logText();
        assertThat(all).contains(logIds.tenant(T1.toString()), logIds.account(ACC));
        assertThat(all).doesNotContain(T1.toString(), ACC + " ", NEW_RT, OLD_RT, AT);
    }

    @Test
    void commitFailureWithoutRotationLosesNothing() {
        db.commitFailure = new SQLException("commit", "08006");

        assertThatThrownBy(this::refresh).isInstanceOf(IllegalStateException.class);

        org.mockito.Mockito.verify(metrics, org.mockito.Mockito.never()).oauthGrantLost(
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void providerNoLongerConfiguredIsReportedWithoutCallingTheEndpoint() {
        configured = Optional.empty();

        assertThat(refresh()).isInstanceOf(RefreshResult.ProviderUnconfigured.class);

        assertThat(clientCalls).isEmpty();
    }

    @Test
    void missingGrantIsInvalidGrantWithoutWrites() {
        RefreshResult res = store.refresh(new TenantId(T1.toString()), new AccountId("nope"));

        assertThat(res).isInstanceOf(RefreshResult.InvalidGrant.class);
        assertThat(clientCalls).isEmpty();
        assertThat(db.committed).noneMatch(s -> s.startsWith("relink"));
    }

    @Test
    void tenantIsolationAnotherTenantsGrantIsNeverReadOrChanged() {
        RefreshResult res = store.refresh(new TenantId(T2.toString()), new AccountId(ACC));

        assertThat(res).isInstanceOf(RefreshResult.InvalidGrant.class);
        assertThat(clientCalls).isEmpty();
        assertThat(db.tenantsTouched).containsOnly(T2);
        assertThat(db.hasCredential(T1, ACC, 3)).isTrue();
    }

    @Test
    void aNonUuidTenantFindsNothing() {
        assertThat(store.refresh(new TenantId("not-a-uuid"), new AccountId(ACC)))
                .isInstanceOf(RefreshResult.InvalidGrant.class);
        assertThat(db.committed).isEmpty();
    }

    @Test
    void aCredentialCopiedToAnotherVersionDoesNotDecryptAndNeverReachesTheEndpoint() {
        db.putRaw(T1, ACC, 3, db.sealed(T1, ACC, 9, OLD_RT, cipher)); // sealed for version 9, stored as 3

        assertThatThrownBy(this::refresh).isInstanceOf(CredentialDecryptException.class);

        assertThat(clientCalls).isEmpty();
        verify(metrics).decryptFailure(new TenantId(T1.toString()));
    }

    @Test
    void noTokenOrCodeOrErrorDescriptionIsEverLogged() {
        answer = granted(NEW_RT);
        refresh();
        answer = new TokenResponse.InvalidGrant();
        refresh();
        db.commitFailure = new SQLException("commit", "08006");
        answer = granted(NEW_RT);
        try {
            refresh();
        } catch (IllegalStateException expected) {
            // lost grant
        }

        assertThat(logText()).doesNotContain(OLD_RT, NEW_RT, AT);
    }

    private String logText() {
        StringBuilder sb = new StringBuilder();
        for (ILoggingEvent e : logs.list) {
            sb.append(e.getFormattedMessage()).append('\n');
            if (e.getThrowableProxy() != null) {
                sb.append(e.getThrowableProxy().getMessage()).append('\n');
            }
        }
        return sb.toString();
    }

    /** In-memory stand-in for the grant tables; applies a transaction's operations only when it commits. */
    static final class FakeDb implements GrantDb {

        record Key(UUID tenant, String account) {
        }

        final Map<Key, Grant> grants = new HashMap<>();
        final Map<String, CredentialCipher.Sealed> credentials = new HashMap<>();
        final List<String> committed = new ArrayList<>();
        final List<EventInfo> events = new ArrayList<>();
        final java.util.Set<UUID> tenantsTouched = new java.util.HashSet<>();
        SQLException lockFailure;
        SQLException commitFailure;

        void putGrant(UUID t, String a, String provider, int version, String refresh, CredentialCipher c) {
            grants.put(new Key(t, a), new Grant(provider, version));
            putRaw(t, a, version, sealed(t, a, version, refresh, c));
        }

        CredentialCipher.Sealed sealed(UUID t, String a, int v, String refresh, CredentialCipher c) {
            return c.seal(t.toString(), a, v, refresh.toCharArray());
        }

        void putRaw(UUID t, String a, int v, CredentialCipher.Sealed s) {
            credentials.put(t + "/" + a + "/" + v, s);
        }

        boolean hasCredential(UUID t, String a, int v) {
            return credentials.containsKey(t + "/" + a + "/" + v);
        }

        String openRefresh(UUID t, String a, int v, CredentialCipher c) {
            try (Secret s = c.open(t.toString(), a, v, credentials.get(t + "/" + a + "/" + v))) {
                return new String(s.chars());
            }
        }

        @Override
        public <T> T inTransaction(TxBody<T> body) throws SQLException {
            List<String> ops = new ArrayList<>();
            List<Runnable> apply = new ArrayList<>();
            List<EventInfo> evs = new ArrayList<>();
            T result = body.run(new Tx() {
                @Override
                public Optional<Grant> lockGrant(UUID t, String a) throws SQLException {
                    tenantsTouched.add(t);
                    if (lockFailure != null) {
                        throw lockFailure;
                    }
                    ops.add("lock");
                    return Optional.ofNullable(grants.get(new Key(t, a)));
                }

                @Override
                public Optional<CredentialCipher.Sealed> readRefresh(UUID t, String a, int v) {
                    tenantsTouched.add(t);
                    ops.add("read:" + v);
                    return Optional.ofNullable(credentials.get(t + "/" + a + "/" + v));
                }

                @Override
                public void insertRefresh(UUID t, String a, int v, CredentialCipher.Sealed s) {
                    ops.add("insert:" + v);
                    apply.add(() -> credentials.put(t + "/" + a + "/" + v, s));
                }

                @Override
                public void pointTo(UUID t, String a, int v) {
                    ops.add("point:" + v);
                    apply.add(() -> grants.computeIfPresent(new Key(t, a), (k, g) -> new Grant(g.providerId(), v)));
                }

                @Override
                public void deleteRefresh(UUID t, String a, int v) {
                    ops.add("delete:" + v);
                    apply.add(() -> credentials.remove(t + "/" + a + "/" + v));
                }

                @Override
                public void markNeedsRelink(UUID t, String a) {
                    ops.add("relink");
                }

                @Override
                public void audit(EventInfo e) {
                    ops.add("audit:" + e.type());
                    evs.add(e);
                }
            });
            if (commitFailure != null) {
                throw commitFailure;
            }
            apply.forEach(Runnable::run);
            committed.addAll(ops);
            events.addAll(evs);
            return result;
        }
    }
}
