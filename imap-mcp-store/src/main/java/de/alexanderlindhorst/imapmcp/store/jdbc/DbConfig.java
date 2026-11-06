package de.alexanderlindhorst.imapmcp.store.jdbc;

import java.util.Objects;

/** Database settings of {@code http-public} (SPEC 5.7: {@code db.url}, {@code db.username}, {@code db.password}, {@code db.maxPoolSize}). */
public record DbConfig(String url, String username, char[] password, int maxPoolSize) {

    public static final int DEFAULT_MAX_POOL_SIZE = 10;
    private static final int MAX_POOL_SIZE_LIMIT = 100;

    public DbConfig {
        Objects.requireNonNull(url, "url");
        Objects.requireNonNull(username, "username");
        Objects.requireNonNull(password, "password");
        if (!url.startsWith("jdbc:postgresql:")) {
            throw new IllegalArgumentException("db.url must be a jdbc:postgresql: URL");
        }
        if (maxPoolSize < 1 || maxPoolSize > MAX_POOL_SIZE_LIMIT) {
            throw new IllegalArgumentException("db.maxPoolSize must be between 1 and " + MAX_POOL_SIZE_LIMIT);
        }
    }

    public static DbConfig of(String url, String username, char[] password) {
        return new DbConfig(url, username, password, DEFAULT_MAX_POOL_SIZE);
    }

    @Override
    public String toString() {
        return "DbConfig[maxPoolSize=" + maxPoolSize + "]";
    }
}
