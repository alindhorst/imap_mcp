package de.alexanderlindhorst.imapmcp.store.jdbc;

import de.alexanderlindhorst.imapmcp.core.web.TenantProvisioner;
import java.util.Objects;
import java.util.Optional;

/** {@link TenantProvisioner} on the cached {@link JdbcTenantDirectory}. */
public final class JdbcTenantProvisioner implements TenantProvisioner {

    private final JdbcTenantDirectory directory;

    public JdbcTenantProvisioner(JdbcTenantDirectory directory) {
        this.directory = Objects.requireNonNull(directory, "directory");
    }

    @Override
    public Optional<TenantRef> find(String issuer, String subject) {
        return directory.find(issuer, subject).map(i -> new TenantRef(i.tenantId(), i.disabled()));
    }

    @Override
    public TenantRef create(String issuer, String subject) {
        JdbcTenantDirectory.TenantInfo info = directory.create(issuer, subject);
        return new TenantRef(info.tenantId(), info.disabled());
    }
}
