package de.alexanderlindhorst.imapmcp.store.jdbc;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;

/** The pooled Postgres data source with the v3b schema migrated (SPEC 5.7). Closing it closes the pool. */
public final class Database implements AutoCloseable {

    private final HikariDataSource dataSource;

    private Database(HikariDataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** Opens the pool and runs the Flyway migrations; startup fails (exception) when either does not work. */
    public static Database open(DbConfig config) {
        HikariConfig hikari = new HikariConfig();
        hikari.setPoolName("imapmcp-db");
        hikari.setJdbcUrl(config.url());
        hikari.setUsername(config.username());
        hikari.setPassword(new String(config.password()));
        hikari.setMaximumPoolSize(config.maxPoolSize());
        HikariDataSource ds = new HikariDataSource(hikari);
        try {
            Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
        } catch (RuntimeException e) {
            ds.close();
            throw e;
        }
        return new Database(ds);
    }

    public DataSource dataSource() {
        return dataSource;
    }

    @Override
    public void close() {
        dataSource.close();
    }
}
