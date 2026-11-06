package de.alexanderlindhorst.imapmcp.store.jdbc;

import de.alexanderlindhorst.imapmcp.core.audit.AuditLog;
import de.alexanderlindhorst.imapmcp.core.audit.AuditWriteException;
import de.alexanderlindhorst.imapmcp.core.audit.CallInfo;
import de.alexanderlindhorst.imapmcp.core.audit.EventInfo;
import de.alexanderlindhorst.imapmcp.core.audit.ItemRef;
import de.alexanderlindhorst.imapmcp.core.crypto.KeyRing;
import java.util.List;
import javax.sql.DataSource;

public final class JdbcAuditLog implements AuditLog {

    public JdbcAuditLog(DataSource dataSource, KeyRing keys) {
    }

    @Override
    public Entry begin(CallInfo call, List<ItemRef> items) throws AuditWriteException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void event(EventInfo event) throws AuditWriteException {
        throw new UnsupportedOperationException();
    }
}
