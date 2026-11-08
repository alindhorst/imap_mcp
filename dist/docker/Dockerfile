# http-public image (SPEC 5.3, 5.7). Same base image (digest) as the http-local image in imap-mcp-app.
# Build context is this directory; only the jar goes in (see .dockerignore): no credentials, no config, no state.
FROM docker.io/library/eclipse-temurin:25.0.5_8-jre-noble@sha256:a98128a57de944a72bd31fbc79b898edd5a5864d3652071d5bbd2160ab7d7dce

# uid/gid 10001: 1000 is taken by "ubuntu" in the base image.
RUN groupadd -g 10001 imapmcp \
 && useradd -u 10001 -g 10001 -M -s /usr/sbin/nologin imapmcp \
 && mkdir -p /config /logs \
 && chown 10001:10001 /logs

ARG JAR_FILE=jar/imap-mcp.jar
COPY ${JAR_FILE} /app/imap-mcp.jar

# http-public has no state directory; logs go only to a file in IMAP_MCP_LOG_DIR. The mode is passed as argument.
ENV IMAP_MCP_CONFIG=/config/config.yaml \
    IMAP_MCP_LOG_DIR=/logs \
    IMAP_MCP_HTTP_BIND=0.0.0.0 \
    IMAP_MCP_HTTP_ALLOW_NON_LOOPBACK_BIND=true

VOLUME ["/logs"]
EXPOSE 8080
USER 10001:10001

# No curl/wget in the image: the probe is a tiny class from the same jar. podman ignores this instruction
# (OCI images carry no HEALTHCHECK), so compose.yaml repeats it.
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
  CMD ["java", "-Xmx16m", "-XX:TieredStopAtLevel=1", "-cp", "/app/imap-mcp.jar", "de.alexanderlindhorst.imapmcp.app.HealthProbe"]

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-XX:+ExitOnOutOfMemoryError", "-jar", "/app/imap-mcp.jar"]
CMD ["--mode", "http-public"]
