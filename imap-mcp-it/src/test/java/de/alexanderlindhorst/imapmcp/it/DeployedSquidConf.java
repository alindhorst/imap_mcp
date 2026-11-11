package de.alexanderlindhorst.imapmcp.it;

/** squid.conf of the forward proxy: CONNECT to *.imap-mcp.test:8443 only, nothing cached, no access log. */
final class DeployedSquidConf {

    private DeployedSquidConf() {
    }

    static String render() {
        return """
                http_port 3128
                acl allowed_ports port 8443
                acl allowed_hosts dstdomain .imap-mcp.test
                acl CONNECT method CONNECT
                http_access allow CONNECT allowed_hosts allowed_ports
                http_access deny all
                cache deny all
                access_log none
                pid_filename /tmp/squid.pid
                """;
    }
}
