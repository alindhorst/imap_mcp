package de.alexanderlindhorst.imapmcp.transport.consumer;

import static org.assertj.core.api.Assertions.assertThat;

import de.alexanderlindhorst.imapmcp.transport.http.HostFilter;
import de.alexanderlindhorst.imapmcp.transport.http.HttpLocalServer;
import de.alexanderlindhorst.imapmcp.transport.http.OriginFilter;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Lives in another package on purpose: the app module must be able to construct and plug in the filters. */
class FilterVisibilityTest {

    @Test
    void appCanConstructAndPlugInFiltersFromAnotherPackage() throws Exception {
        assertThat(HostFilter.DEFAULT_ALLOWED_HOSTS).containsExactly("localhost:*", "127.0.0.1:*", "[::1]:*");
        HttpLocalServer server = HttpLocalServer.builder().port(0)
                .hostFilter(new HostFilter(HostFilter.DEFAULT_ALLOWED_HOSTS))
                .originFilter(new OriginFilter(List.of())).build();
        server.start();
        try {
            assertThat(server.port()).isPositive();
        } finally {
            server.stop();
        }
    }
}
