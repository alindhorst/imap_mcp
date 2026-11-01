# http-local image (SPEC 2, 5.2, 5.3, 5.7). Plain Dockerfile (not Jib) because Jib cannot set HEALTHCHECK.
# Build context is the imap-mcp-app module directory; see the -Pdocker profile in the module pom.
FROM eclipse-temurin:25.0.5_8-jre-noble@sha256:a98128a57de944a72bd31fbc79b898edd5a5864d3652071d5bbd2160ab7d7dce

# uid/gid 10001: 1000 is taken by "ubuntu" in the base image.
RUN groupadd -g 10001 imapmcp \
 && useradd -u 10001 -g 10001 -M -s /usr/sbin/nologin imapmcp \
 && mkdir -p /config /logs \
 && chown 10001:10001 /config /logs

# Only the shaded jar goes in: no credentials, no config, no state.
ARG JAR_FILE
COPY ${JAR_FILE} /app/imap-mcp.jar

ENV IMAP_MCP_CONFIG=/config/config.yaml \
    IMAP_MCP_STATE_DIR=/config/state \
    IMAP_MCP_LOG_DIR=/logs \
    IMAP_MCP_HTTP_BIND=0.0.0.0 \
    IMAP_MCP_HTTP_ALLOW_NON_LOOPBACK_BIND=true

VOLUME ["/config", "/logs"]
EXPOSE 8080
USER 10001

# No curl/wget in the image: the probe is a tiny class from the same jar (no logging init).
HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
  CMD ["java", "-Xmx16m", "-XX:TieredStopAtLevel=1", "-cp", "/app/imap-mcp.jar", "de.alexanderlindhorst.imapmcp.app.HealthProbe"]

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/imap-mcp.jar"]
CMD ["--mode", "http-local"]
