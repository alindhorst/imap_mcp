package de.alexanderlindhorst.imapmcp.store.jdbc;

import de.alexanderlindhorst.imapmcp.core.audit.AuditLog;
import de.alexanderlindhorst.imapmcp.core.audit.AuditReferences;
import de.alexanderlindhorst.imapmcp.core.audit.AuditWriteException;
import de.alexanderlindhorst.imapmcp.core.audit.CallInfo;
import de.alexanderlindhorst.imapmcp.core.audit.Completion;
import de.alexanderlindhorst.imapmcp.core.audit.EventInfo;
import de.alexanderlindhorst.imapmcp.core.audit.ItemRef;
import de.alexanderlindhorst.imapmcp.core.audit.ItemResult;
import de.alexanderlindhorst.imapmcp.core.crypto.KeyRing;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The multi-tenant audit log on {@code audit_event} (SPEC 5.3, 5.7). Per item one row holding the truncated
 * HMAC under the signing key's {@code audit} subkey and its key id ({@link AuditReferences}); folder names, UIDs
 * and handles are hashed in memory and never reach the database. {@code begin} inserts all rows of a call in
 * one transaction as {@code pending}; the returned entry completes them. Every statement is scoped by tenant on
 * update. A failure is an {@link AuditWriteException} without message or cause (fail-closed, D5); the log line
 * names the SQLSTATE only.
 */
public final class JdbcAuditLog implements AuditLog {

    private static final Logger LOG = LoggerFactory.getLogger(JdbcAuditLog.class);
    private static final String TOOL_CALL = "tool_call";

    private final DataSource dataSource;
    private final AuditReferences refs;

    public JdbcAuditLog(DataSource dataSource, KeyRing keys) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.refs = new AuditReferences(Objects.requireNonNull(keys, "keys"));
    }

    @Override
    public Entry begin(CallInfo call, List<ItemRef> items) throws AuditWriteException {
        try (Connection c = dataSource.getConnection()) {
            UUID tenant = UUID.fromString(call.tenant().value());
            c.setAutoCommit(false);
            try {
                List<Long> ids = new ArrayList<>();
                String sql = "INSERT INTO audit_event (tenant_id, account_id, request_id, event_type, tool, ref_hash,"
                        + " ref_key_id, item_status, confirmation) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id";
                try (PreparedStatement ps = c.prepareStatement(sql)) {
                    if (items.isEmpty()) {
                        ids.add(insert(ps, tenant, call, null, null));
                    }
                    for (ItemRef item : items) {
                        AuditReferences.Ref ref = refs.ref(call.tenant(), call.account(), item);
                        ids.add(insert(ps, tenant, call, ref, "pending"));
                    }
                }
                c.commit();
                return new JdbcEntry(tenant, List.copyOf(ids), !items.isEmpty());
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException | RuntimeException e) {
            throw failed(e);
        }
    }

    private static long insert(PreparedStatement ps, UUID tenant, CallInfo call, AuditReferences.Ref ref, String status)
            throws SQLException {
        ps.setObject(1, tenant);
        ps.setString(2, call.account() == null ? null : call.account().value());
        ps.setString(3, call.requestId());
        ps.setString(4, TOOL_CALL);
        ps.setString(5, call.tool());
        if (ref == null) {
            ps.setNull(6, Types.BINARY);
            ps.setNull(7, Types.SMALLINT);
        } else {
            ps.setBytes(6, ref.hash());
            ps.setShort(7, (short) ref.keyId());
        }
        ps.setString(8, status);
        ps.setString(9, call.confirmation());
        try (ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
    }

    @Override
    public void event(EventInfo event) throws AuditWriteException {
        try (Connection c = dataSource.getConnection()) {
            insertEvent(c, event);
        } catch (SQLException | RuntimeException e) {
            throw failed(e);
        }
    }

    /** The event row on the caller's connection, so that it commits or rolls back with the action it records. */
    static void insertEvent(Connection c, EventInfo event) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO audit_event (tenant_id, account_id, request_id,"
                + " event_type, result, completed_at) VALUES (?, ?, ?, ?, ?, now())")) {
            ps.setObject(1, UUID.fromString(event.tenant().value()));
            ps.setString(2, event.account() == null ? null : event.account().value());
            ps.setString(3, event.requestId());
            ps.setString(4, event.type());
            ps.setString(5, event.result());
            ps.executeUpdate();
        }
    }

    private static AuditWriteException failed(Exception e) {
        LOG.error("audit write failed (cause={}, sqlstate={})", e.getClass().getSimpleName(),
                e instanceof SQLException s ? s.getSQLState() : "-");
        return new AuditWriteException();
    }

    private final class JdbcEntry implements Entry {
        private final UUID tenant;
        private final List<Long> ids;
        private final boolean perItem;

        JdbcEntry(UUID tenant, List<Long> ids, boolean perItem) {
            this.tenant = tenant;
            this.ids = ids;
            this.perItem = perItem;
        }

        @Override
        public void complete(Completion completion) throws AuditWriteException {
            if (perItem && completion.items().size() != ids.size()) {
                throw failed(new IllegalStateException("item count"));
            }
            String sql = "UPDATE audit_event SET item_status = ?, error_code = ?, confirmation = ?, result = ?,"
                    + " completed_at = now() WHERE id = ? AND tenant_id = ?";
            try (Connection c = dataSource.getConnection()) {
                c.setAutoCommit(false);
                try (PreparedStatement ps = c.prepareStatement(sql)) {
                    for (int i = 0; i < ids.size(); i++) {
                        ItemResult item = perItem ? completion.items().get(i) : null;
                        ps.setString(1, item == null ? null : item.status());
                        ps.setString(2, item == null ? completion.errorCode() : coalesce(item.errorCode(),
                                completion.errorCode()));
                        ps.setString(3, completion.confirmation());
                        ps.setString(4, completion.result());
                        ps.setLong(5, ids.get(i));
                        ps.setObject(6, tenant);
                        ps.addBatch();
                    }
                    ps.executeBatch();
                    c.commit();
                } catch (SQLException | RuntimeException e) {
                    c.rollback();
                    throw e;
                }
            } catch (SQLException | RuntimeException e) {
                throw failed(e);
            }
        }

        private String coalesce(String a, String b) {
            return a != null ? a : b;
        }
    }
}
