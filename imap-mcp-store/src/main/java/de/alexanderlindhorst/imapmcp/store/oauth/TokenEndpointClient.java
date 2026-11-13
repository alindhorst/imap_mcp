package de.alexanderlindhorst.imapmcp.store.oauth;

import de.alexanderlindhorst.imapmcp.core.account.Secret;
import de.alexanderlindhorst.imapmcp.core.net.PinnedSocketFactory;
import de.alexanderlindhorst.imapmcp.core.net.SsrfPolicy;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The token-endpoint client of {@code http-public} (SPEC 5.3 "Upstream token egress"). A minimal HTTP/1.1 POST over
 * a TLS socket of its own, because the JDK {@code HttpClient} resolves the host itself and so cannot pin the
 * address that was checked: our own DNS resolution (every answer is checked against the block list, one blocked
 * answer refuses the call), connect to the first checked address, TLS with SNI and the HTTPS endpoint
 * identification against the host name, no proxy, no redirects, no keep-alive, 5 s to connect and 8 s for the whole
 * exchange, a 64 KiB cap and {@code application/json} only. Never logged: bodies, {@code error_description},
 * tokens, codes, verifiers, hosts; only allowlisted {@code error} codes and failure classes.
 */
public final class TokenEndpointClient implements TokenEndpoint {

    private static final Logger LOG = LoggerFactory.getLogger(TokenEndpointClient.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final int HTTPS_PORT = 443;
    private static final int MAX_HEAD = 8 * 1024;
    private static final int MAX_BODY = 64 * 1024;
    private static final long MAX_EXPIRES_IN = 86_400;
    private static final long MAX_RETRY_AFTER = 3_600;
    private static final Pattern STATUS = Pattern.compile("HTTP/1\\.[01] (\\d{3})(?: .*)?");
    private static final Pattern NUMERIC_HOST = Pattern.compile("[0-9.]+|0[xX][0-9a-fA-F.]*");
    /** RFC 6749 5.2 codes (and the usual extensions) that may appear in a log line; anything else is not logged. */
    private static final Set<String> LOGGABLE_ERRORS = Set.of("invalid_request", "invalid_client", "invalid_grant",
            "unauthorized_client", "unsupported_grant_type", "invalid_scope", "access_denied", "server_error",
            "temporarily_unavailable", "interaction_required");

    private final Predicate<InetAddress> blocked;
    private final PinnedSocketFactory.Resolver resolver;
    private final SSLContext ssl;
    private final int port;
    private final Duration connectTimeout;
    private final Duration totalTimeout;
    private final Clock clock;

    /** Production client: JDK default trust store (and a configured one via the standard system properties). */
    public TokenEndpointClient(SsrfPolicy policy) {
        this(policy, defaultContext());
    }

    public TokenEndpointClient(SsrfPolicy policy, SSLContext ssl) {
        this(Objects.requireNonNull(policy, "policy")::isBlocked, InetAddress::getAllByName, ssl, HTTPS_PORT,
                Duration.ofSeconds(5), Duration.ofSeconds(8), Clock.systemUTC());
    }

    /** Test seam: block predicate, resolver, TLS context, the one allowed port, timeouts and clock. */
    TokenEndpointClient(Predicate<InetAddress> blocked, PinnedSocketFactory.Resolver resolver, SSLContext ssl,
            int port, Duration connect, Duration total, Clock clock) {
        this.blocked = Objects.requireNonNull(blocked, "blocked");
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.ssl = Objects.requireNonNull(ssl, "ssl");
        this.port = port;
        this.connectTimeout = connect;
        this.totalTimeout = total;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    private static SSLContext defaultContext() {
        try {
            return SSLContext.getDefault();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("no default SSL context");
        }
    }

    @Override
    public TokenResponse refresh(TokenProvider provider, char[] refreshToken) {
        return post(provider, Map.of("grant_type", "refresh_token", "refresh_token", new String(refreshToken)));
    }

    @Override
    public TokenResponse exchangeCode(TokenProvider provider, char[] code, char[] codeVerifier, URI redirectUri) {
        return post(provider, Map.of("grant_type", "authorization_code", "code", new String(code), "code_verifier",
                new String(codeVerifier), "redirect_uri", redirectUri.toString()));
    }

    /** A response that is not usable; carries a fixed reason code, never response text. */
    private static final class Bad extends Exception {
        Bad(String reason) {
            super(reason, null, false, false);
        }
    }

    private TokenResponse post(TokenProvider provider, Map<String, String> form) {
        URI uri = provider.tokenEndpoint();
        String host = uri.getHost();
        int uriPort = uri.getPort() == -1 ? HTTPS_PORT : uri.getPort();
        if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null || uri.getUserInfo() != null
                || uriPort != port || host.contains(":") || host.startsWith("[") || NUMERIC_HOST.matcher(
                host.substring(host.lastIndexOf('.') + 1)).matches()) {
            LOG.warn("token endpoint refused: not https on the allowed port, or an IP literal");
            return new TokenResponse.Refused();
        }
        host = host.toLowerCase(Locale.ROOT);
        long deadline = System.nanoTime() + totalTimeout.toNanos();
        InetSocketAddress target;
        try {
            InetAddress[] answers = resolver.resolve(host);
            if (answers == null || answers.length == 0) {
                return new TokenResponse.Unavailable(null);
            }
            for (InetAddress a : answers) {
                if (blocked.test(a)) {
                    LOG.warn("token endpoint refused: address blocked");
                    return new TokenResponse.Refused();
                }
            }
            InetAddress first = Arrays.stream(answers)
                    .min(Comparator.comparingInt((InetAddress a) -> a.getAddress().length)).orElseThrow();
            target = new InetSocketAddress(InetAddress.getByAddress(host, first.getAddress()), port);
        } catch (IOException e) {
            LOG.warn("token endpoint call failed: resolve");
            return new TokenResponse.Unavailable(null);
        }
        byte[] request = request(provider, uri, host, form);
        try (Socket plain = new Socket(Proxy.NO_PROXY)) {
            plain.connect(target, (int) Math.max(1, Math.min(connectTimeout.toMillis(), remainingMillis(deadline))));
            // Closing the plain socket only: a TLS close_notify exchange could outlast the deadline.
            {
                SSLSocket tls = (SSLSocket) ssl.getSocketFactory().createSocket(plain, host, port, true);
                SSLParameters params = tls.getSSLParameters();
                params.setEndpointIdentificationAlgorithm("HTTPS");
                params.setServerNames(List.of(new SNIHostName(host)));
                tls.setSSLParameters(params);
                tls.setSoTimeout((int) remainingMillis(deadline));
                tls.startHandshake();
                OutputStream out = tls.getOutputStream();
                out.write(request);
                out.flush();
                return read(new DeadlineInput(tls, deadline));
            }
        } catch (Bad e) {
            LOG.warn("token endpoint answer unusable: {}", e.getMessage());
            return new TokenResponse.Malformed();
        } catch (IOException e) {
            LOG.warn("token endpoint call failed: {}", e instanceof SocketTimeoutException ? "timeout"
                    : e.getClass().getSimpleName());
            return new TokenResponse.Unavailable(null);
        } finally {
            Arrays.fill(request, (byte) 0);
        }
    }

    private static long remainingMillis(long deadline) throws SocketTimeoutException {
        long ms = (deadline - System.nanoTime()) / 1_000_000;
        if (ms <= 0) {
            throw new SocketTimeoutException();
        }
        return ms;
    }

    private byte[] request(TokenProvider provider, URI uri, String host, Map<String, String> form) {
        StringBuilder body = new StringBuilder();
        Map<String, String> all = new java.util.LinkedHashMap<>(form);
        all.put("client_id", provider.clientId());
        for (Map.Entry<String, String> e : all.entrySet()) {
            body.append(body.isEmpty() ? "" : "&").append(e.getKey()).append('=')
                    .append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
        }
        try (Secret secret = provider.clientSecret().get()) {
            body.append("&client_secret=").append(URLEncoder.encode(new String(secret.chars()), StandardCharsets.UTF_8));
        }
        byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
        String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        if (uri.getRawQuery() != null) {
            path += "?" + uri.getRawQuery();
        }
        String head = "POST " + path + " HTTP/1.1\r\nHost: " + host + (port == HTTPS_PORT ? "" : ":" + port)
                + "\r\nContent-Type: application/x-www-form-urlencoded\r\nAccept: application/json\r\n"
                + "Content-Length: " + payload.length + "\r\nConnection: close\r\nUser-Agent: imap-mcp\r\n\r\n";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(head.getBytes(StandardCharsets.US_ASCII));
        out.writeBytes(payload);
        Arrays.fill(payload, (byte) 0);
        return out.toByteArray();
    }

    private TokenResponse read(InputStream in) throws IOException, Bad {
        String head = readHead(in);
        String[] lines = head.split("\r\n");
        var status = STATUS.matcher(lines[0]);
        if (!status.matches()) {
            throw new Bad("status_line");
        }
        int code = Integer.parseInt(status.group(1));
        Map<String, String> headers = new HashMap<>();
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon <= 0) {
                throw new Bad("header");
            }
            if (headers.put(lines[i].substring(0, colon).trim().toLowerCase(Locale.ROOT),
                    lines[i].substring(colon + 1).trim()) != null) {
                throw new Bad("duplicate_header");
            }
        }
        if (code / 100 == 3) {
            throw new Bad("redirect");
        }
        if (code >= 500 || code == 429) {
            return new TokenResponse.Unavailable(retryAfter(headers.get("retry-after")));
        }
        if (code / 100 != 2 && code / 100 != 4) {
            throw new Bad("status");
        }
        String type = headers.getOrDefault("content-type", "");
        String media = type.contains(";") ? type.substring(0, type.indexOf(';')) : type;
        if (!"application/json".equalsIgnoreCase(media.trim())) {
            throw new Bad("content_type");
        }
        JsonNode json;
        try {
            json = JSON.readTree(body(in, headers));
        } catch (RuntimeException e) {
            throw new Bad("unparsable");
        }
        if (json == null || !json.isObject()) {
            throw new Bad("unparsable");
        }
        if (json.has("error")) {
            return mapError(json.path("error").asString(""));
        }
        if (code / 100 != 2) {
            throw new Bad("status");
        }
        return granted(json);
    }

    private static TokenResponse mapError(String error) throws Bad {
        LOG.warn("token endpoint error={}", LOGGABLE_ERRORS.contains(error) ? error : "unlisted");
        return switch (error) {
            case "invalid_grant", "invalid_scope" -> new TokenResponse.InvalidGrant();
            case "invalid_client" -> new TokenResponse.InvalidClient();
            case "unauthorized_client" -> new TokenResponse.UnauthorizedClient();
            default -> throw new Bad("error_code");
        };
    }

    private TokenResponse granted(JsonNode json) throws Bad {
        String access = json.path("access_token").asString("");
        long expiresIn = expiresIn(json.path("expires_in"));
        if (access.isEmpty() || expiresIn <= 0) {
            throw new Bad("fields");
        }
        String refresh = json.path("refresh_token").asString("");
        String scope = json.path("scope").asString("");
        return new TokenResponse.Granted(new Secret(access.toCharArray()),
                clock.instant().plusSeconds(Math.min(expiresIn, MAX_EXPIRES_IN)),
                refresh.isEmpty() ? null : new Secret(refresh.toCharArray()), scope.isEmpty() ? null : scope);
    }

    private static long expiresIn(JsonNode n) {
        if (n.isNumber()) {
            return n.asLong(0);
        }
        try {
            return n.isString() ? Long.parseLong(n.asString("")) : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private Duration retryAfter(String value) {
        if (value == null) {
            return null;
        }
        try {
            if (value.matches("\\d{1,9}")) {
                return Duration.ofSeconds(Math.min(Long.parseLong(value), MAX_RETRY_AFTER));
            }
            Instant at = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
            long s = Duration.between(clock.instant(), at).getSeconds();
            return Duration.ofSeconds(Math.max(0, Math.min(s, MAX_RETRY_AFTER)));
        } catch (DateTimeParseException | NumberFormatException e) {
            return null;
        }
    }

    private static String readHead(InputStream in) throws IOException, Bad {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int state = 0;
        while (state < 4) {
            int b = in.read();
            if (b < 0) {
                throw new IOException("eof");
            }
            if (head.size() >= MAX_HEAD) {
                throw new Bad("head_size");
            }
            head.write(b);
            state = (b == '\r' && (state == 0 || state == 2)) || (b == '\n' && (state == 1 || state == 3))
                    ? state + 1 : (b == '\r' ? 1 : 0);
        }
        String text = head.toString(StandardCharsets.ISO_8859_1);
        return text.substring(0, text.length() - 4);
    }

    private static byte[] body(InputStream in, Map<String, String> headers) throws IOException, Bad {
        String te = headers.get("transfer-encoding");
        if (te != null) {
            if (!"chunked".equalsIgnoreCase(te.trim())) {
                throw new Bad("transfer_encoding");
            }
            return chunked(in);
        }
        String length = headers.get("content-length");
        if (length == null || !length.matches("\\d{1,9}")) {
            throw new Bad("no_length");
        }
        int n = Integer.parseInt(length);
        if (n > MAX_BODY) {
            throw new Bad("oversized");
        }
        byte[] body = in.readNBytes(n);
        if (body.length < n) {
            throw new IOException("eof");
        }
        return body;
    }

    private static byte[] chunked(InputStream in) throws IOException, Bad {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int consumed = 0;
        while (true) {
            String line = readLine(in);
            consumed += line.length() + 2;
            String hex = line.contains(";") ? line.substring(0, line.indexOf(';')) : line;
            if (!hex.trim().matches("[0-9a-fA-F]{1,8}")) {
                throw new Bad("chunk");
            }
            long size = Long.parseLong(hex.trim(), 16);
            consumed += size + 2;
            if (consumed > MAX_BODY || size > MAX_BODY) {
                throw new Bad("oversized");
            }
            if (size == 0) {
                String trailer;
                do {
                    trailer = readLine(in);
                    consumed += trailer.length() + 2;
                    if (consumed > MAX_BODY) {
                        throw new Bad("oversized");
                    }
                } while (!trailer.isEmpty());
                return out.toByteArray();
            }
            byte[] chunk = in.readNBytes((int) size);
            if (chunk.length < size || in.read() != '\r' || in.read() != '\n') {
                throw new IOException("eof");
            }
            out.writeBytes(chunk);
        }
    }

    private static String readLine(InputStream in) throws IOException, Bad {
        StringBuilder sb = new StringBuilder();
        while (true) {
            int b = in.read();
            if (b < 0) {
                throw new IOException("eof");
            }
            if (b == '\r') {
                if (in.read() != '\n') {
                    throw new Bad("chunk");
                }
                return sb.toString();
            }
            if (sb.length() > 1024) {
                throw new Bad("chunk");
            }
            sb.append((char) b);
        }
    }

    /** Reads with the socket timeout recomputed from the overall deadline, so a slow drip cannot outlast it. */
    private static final class DeadlineInput extends InputStream {
        private final SSLSocket socket;
        private final InputStream in;
        private final long deadline;

        DeadlineInput(SSLSocket socket, long deadline) throws IOException {
            this.socket = socket;
            this.in = new java.io.BufferedInputStream(socket.getInputStream());
            this.deadline = deadline;
        }

        @Override
        public int read() throws IOException {
            socket.setSoTimeout((int) remainingMillis(deadline));
            return in.read();
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            socket.setSoTimeout((int) remainingMillis(deadline));
            return in.read(b, off, len);
        }
    }
}
