package de.alexanderlindhorst.imapmcp.store.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class JdbcTenantDirectoryTest {

    @Test
    void theTenantCacheTimeIsThirtySecondsPerSpecD13() {
        assertThat(JdbcTenantDirectory.CACHE_TTL).isEqualTo(Duration.ofSeconds(30));
    }
}
