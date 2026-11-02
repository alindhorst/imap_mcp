package de.alexanderlindhorst.imapmcp.core.tools;

import de.alexanderlindhorst.imapmcp.core.account.AccountRepository;
import de.alexanderlindhorst.imapmcp.core.account.MailAccount;
import de.alexanderlindhorst.imapmcp.core.account.PoolKey;
import de.alexanderlindhorst.imapmcp.core.account.ResolvedAccount;
import de.alexanderlindhorst.imapmcp.core.error.ErrorCode;
import de.alexanderlindhorst.imapmcp.core.error.ImapErrorMapper;
import de.alexanderlindhorst.imapmcp.core.imap.CapabilityCache;
import de.alexanderlindhorst.imapmcp.core.imap.ImapCapabilities;
import de.alexanderlindhorst.imapmcp.core.imap.pool.StoreLeases;
import de.alexanderlindhorst.imapmcp.core.tool.ToolCallContext;
import de.alexanderlindhorst.imapmcp.core.tool.ToolDefinition;
import de.alexanderlindhorst.imapmcp.core.tool.ToolHandler;
import de.alexanderlindhorst.imapmcp.core.tool.ToolHints;
import de.alexanderlindhorst.imapmcp.core.tool.ToolOutcome;
import jakarta.mail.MessagingException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code list_accounts} (SPEC 4): the tenant's linked mailboxes with a capability summary. Capabilities come
 * from the {@link CapabilityCache}; a Missing or Expired entry is refreshed by leasing a connection through
 * {@link StoreLeases} (admission and timeouts apply) and reading the capabilities right after login. No folder
 * is ever opened. A failing account never fails the call: it gets {@code auth_failed} or {@code unreachable}
 * and its stale capabilities if cached. Addresses are returned but never logged.
 */
public final class ListAccountsTool implements ToolHandler {

    private static final Logger LOG = LoggerFactory.getLogger(ListAccountsTool.class);

    public static final String NAME = "list_accounts";

    static final Map<String, Object> INPUT_SCHEMA = schema("type", "object", "properties", Map.of(),
            "additionalProperties", false);

    private static final Map<String, Object> CAPABILITIES_SCHEMA = schema("type", List.of("object", "null"),
            "properties", schema("move", schema("type", "boolean"), "uidplus", schema("type", "boolean"),
                    "special_use", schema("type", "boolean"), "sort", schema("type", "boolean"),
                    "gmail", schema("type", "boolean")),
            "required", List.of("move", "uidplus", "special_use", "sort", "gmail"));

    private static final Map<String, Object> ITEM_SCHEMA = schema("type", "object",
            "properties", schema("account", schema("type", "string"),
                    "label", schema("type", "string"),
                    "address", schema("type", List.of("string", "null")),
                    "provider", schema("type", "string", "enum", List.of("generic", "gmail")),
                    "status", schema("type", "string", "enum", List.of("ok", "auth_failed", "unreachable", "unknown")),
                    "capabilities", CAPABILITIES_SCHEMA,
                    "capabilities_detected_at", schema("type", List.of("string", "null"))),
            "required", List.of("account", "label", "address", "provider", "status", "capabilities",
                    "capabilities_detected_at"));

    static final Map<String, Object> OUTPUT_SCHEMA = schema("type", "object",
            "properties", schema("accounts", schema("type", "array", "items", ITEM_SCHEMA)),
            "required", List.of("accounts"));

    public static final ToolDefinition DEFINITION = new ToolDefinition(NAME, "List mail accounts",
            "List the mail accounts linked for this user, with a label, an optional address, the provider, "
                    + "connection status and a capability summary. Use the returned account handle as the account "
                    + "argument of every other tool. Read-only; does not change flags or mark messages as read.",
            INPUT_SCHEMA, OUTPUT_SCHEMA, ToolHints.READ_ONLY_OPEN_WORLD);

    private final AccountRepository accounts;
    private final CapabilityCache capabilities;
    private final StoreLeases leases;
    private final int parallelism;

    public ListAccountsTool(AccountRepository accounts, CapabilityCache capabilities, StoreLeases leases,
            int parallelism) {
        this.accounts = Objects.requireNonNull(accounts, "accounts");
        this.capabilities = Objects.requireNonNull(capabilities, "capabilities");
        this.leases = Objects.requireNonNull(leases, "leases");
        if (parallelism < 1) {
            throw new IllegalArgumentException("parallelism must be positive");
        }
        this.parallelism = parallelism;
    }

    @Override
    public ToolOutcome handle(ToolCallContext ctx, Map<String, Object> args) {
        List<ResolvedAccount> mine = accounts.findByTenant(ctx.accountContext().tenantId());
        List<Map<String, Object>> entries = new ArrayList<>(describe(ctx, mine));
        entries.sort((a, b) -> {
            int c = compareCodePoints((String) a.get("label"), (String) b.get("label"));
            return c != 0 ? c : compareCodePoints((String) a.get("account"), (String) b.get("account"));
        });
        return new ToolOutcome.Success(Map.of("accounts", entries));
    }

    private List<Map<String, Object>> describe(ToolCallContext ctx, List<ResolvedAccount> mine) {
        if (mine.isEmpty()) {
            return List.of();
        }
        Semaphore limit = new Semaphore(parallelism);
        try (ExecutorService vt = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Map<String, Object>>> futures = new ArrayList<>();
            for (ResolvedAccount r : mine) {
                Callable<Map<String, Object>> job = () -> {
                    limit.acquire();
                    try {
                        return entry(ctx, r);
                    } finally {
                        limit.release();
                    }
                };
                futures.add(vt.submit(job));
            }
            List<Map<String, Object>> out = new ArrayList<>();
            for (Future<Map<String, Object>> f : futures) {
                out.add(f.get());
            }
            return out;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        } catch (ExecutionException e) {
            throw e.getCause() instanceof RuntimeException re ? re : new IllegalStateException(e.getCause());
        }
    }

    private Map<String, Object> entry(ToolCallContext ctx, ResolvedAccount r) {
        PoolKey key = PoolKey.of(ctx.accountContext(), r);
        String status = "ok";
        if (!(capabilities.get(key) instanceof CapabilityCache.Lookup.Fresh)) {
            status = refresh(ctx, key);
        }
        CapabilityCache.Lookup lookup = capabilities.get(key);
        ImapCapabilities caps = null;
        Instant detectedAt = null;
        if (lookup instanceof CapabilityCache.Lookup.Fresh f) {
            caps = f.capabilities();
            detectedAt = f.detectedAt();
        } else if (lookup instanceof CapabilityCache.Lookup.Expired e) {
            caps = e.capabilities();
            detectedAt = e.detectedAt();
        }
        MailAccount a = r.account();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("account", r.handle());
        m.put("label", a.label());
        m.put("address", a.address());
        m.put("provider", a.flavor() == MailAccount.Flavor.GMAIL ? "gmail" : "generic");
        m.put("status", status);
        m.put("capabilities", caps == null ? null : capabilityMap(caps));
        m.put("capabilities_detected_at", detectedAt == null ? null : detectedAt.truncatedTo(ChronoUnit.SECONDS).toString());
        return m;
    }

    /** Probes with a short-lived lease; returns the account status. Never throws for connection problems. */
    private String refresh(ToolCallContext ctx, PoolKey key) {
        try {
            return leases.withLease(key, ctx, lease -> {
                // A reused pooled Store did not run makeObject, so read the capabilities explicitly (local, no I/O).
                capabilities.put(key, ImapCapabilities.from(lease.store()));
                return "ok";
            });
        } catch (MessagingException | RuntimeException e) {
            ErrorCode code = ImapErrorMapper.map(e, ctx.requestId()).error().code();
            LOG.debug("account probe failed: {}", code);
            return statusFor(code);
        }
    }

    static String statusFor(ErrorCode code) {
        return code == ErrorCode.AUTH_FAILED ? "auth_failed" : "unreachable";
    }

    private static Map<String, Object> capabilityMap(ImapCapabilities c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("move", c.move());
        m.put("uidplus", c.uidplus());
        m.put("special_use", c.specialUse());
        m.put("sort", c.sort());
        m.put("gmail", c.gmail());
        return m;
    }

    /** Unicode code point order (String.compareTo compares UTF-16 units, which differs for supplementary characters). */
    static int compareCodePoints(String a, String b) {
        int i = 0;
        int j = 0;
        while (i < a.length() && j < b.length()) {
            int ca = a.codePointAt(i);
            int cb = b.codePointAt(j);
            if (ca != cb) {
                return Integer.compare(ca, cb);
            }
            i += Character.charCount(ca);
            j += Character.charCount(cb);
        }
        return Integer.compare(a.length() - i, b.length() - j);
    }

    private static Map<String, Object> schema(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }
}
