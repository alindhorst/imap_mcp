package de.alexanderlindhorst.imapmcp.it;

import de.alexanderlindhorst.imapmcp.store.jdbc.DbConfig;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** Postgres container on a dynamic port (SPEC 5.8); the image is pinned by digest in {@link ItImages}. */
public final class PostgresFixture extends PostgreSQLContainer {

    public PostgresFixture() {
        super(DockerImageName.parse(ItImages.POSTGRES).asCompatibleSubstituteFor("postgres"));
        withStartupAttempts(3); // see KeycloakFixture
    }

    /** Connection settings of the started container. */
    public DbConfig config() {
        return DbConfig.of(getJdbcUrl(), getUsername(), getPassword().toCharArray());
    }
}
