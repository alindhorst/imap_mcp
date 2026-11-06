package de.alexanderlindhorst.imapmcp.transport.http.web;

import de.alexanderlindhorst.imapmcp.core.audit.AuditLog;
import de.alexanderlindhorst.imapmcp.core.audit.EventInfo;
import de.alexanderlindhorst.imapmcp.core.tenancy.TenantId;
import de.alexanderlindhorst.imapmcp.core.web.OidcPendingStore;
import de.alexanderlindhorst.imapmcp.core.web.TenantProvisioner;
import de.alexanderlindhorst.imapmcp.core.web.WebSession;
import de.alexanderlindhorst.imapmcp.core.web.WebSessionStore;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Test doubles of the persistence interfaces; the JDBC versions are tested in imap-mcp-it. */
final class InMemoryStores {

    private InMemoryStores() {
    }

    static final class Sessions implements WebSessionStore {
        final Map<String, WebSession> byId = new HashMap<>();
        private final SecureRandom random = new SecureRandom();

        private String token() {
            byte[] b = new byte[32];
            random.nextBytes(b);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
        }

        @Override
        public synchronized Created create(TenantId tenant, Instant authTime) {
            String id = token();
            String csrf = token();
            byId.put(id, new WebSession(tenant, csrf, authTime, Instant.now()));
            return new Created(id, csrf);
        }

        @Override
        public synchronized Optional<WebSession> find(String sessionId) {
            return Optional.ofNullable(byId.get(sessionId));
        }

        @Override
        public synchronized void delete(String sessionId) {
            byId.remove(sessionId);
        }

        synchronized int size() {
            return byId.size();
        }
    }

    static final class Pending implements OidcPendingStore {
        final Map<String, OidcPendingStore.Pending> byState = new HashMap<>();
        int limit = Integer.MAX_VALUE;
        private final SecureRandom random = new SecureRandom();

        @Override
        public synchronized String begin(String nonce, String pkceVerifier) {
            if (byState.size() >= limit) {
                throw new LimitReachedException();
            }
            byte[] b = new byte[32];
            random.nextBytes(b);
            String state = Base64.getUrlEncoder().withoutPadding().encodeToString(b);
            byState.put(state, new OidcPendingStore.Pending(nonce, pkceVerifier));
            return state;
        }

        @Override
        public synchronized Optional<OidcPendingStore.Pending> consume(String state) {
            return Optional.ofNullable(byState.remove(state));
        }

        synchronized int size() {
            return byState.size();
        }
    }

    static final class Tenants implements TenantProvisioner {
        final Map<String, TenantRef> bySubject = new HashMap<>();
        final AuditLog audit;
        int created;

        Tenants(AuditLog audit) {
            this.audit = audit;
        }

        @Override
        public synchronized Optional<TenantRef> find(String issuer, String subject) {
            return Optional.ofNullable(bySubject.get(issuer + "|" + subject));
        }

        @Override
        public synchronized TenantRef create(String issuer, String subject, String requestId) {
            TenantRef existing = bySubject.get(issuer + "|" + subject);
            if (existing != null) {
                return existing;
            }
            TenantRef ref = new TenantRef(new TenantId(UUID.randomUUID().toString()), false);
            // like the JDBC version: the audit row first, so that a failed write changes nothing (fail-closed)
            audit.event(new EventInfo("tenant_created", ref.tenantId(), null, requestId, "ok"));
            created++;
            bySubject.put(issuer + "|" + subject, ref);
            return ref;
        }

        synchronized void disable(String issuer, String subject) {
            TenantRef t = bySubject.get(issuer + "|" + subject);
            bySubject.put(issuer + "|" + subject, new TenantRef(t.tenantId(), true));
        }
    }
}
