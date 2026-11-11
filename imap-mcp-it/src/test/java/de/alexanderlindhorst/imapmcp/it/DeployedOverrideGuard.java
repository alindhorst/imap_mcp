package de.alexanderlindhorst.imapmcp.it;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * Checks a test-only compose override against the shipped compose.yaml (SPEC 5.8, deployed end-to-end test). The
 * allowlist is here, in test code, so the override cannot ship its own exemptions. A key not named is a violation.
 * The copy's env file (COMPOSE_PROFILES, CADDY_SITE, BACKEND_SUBNET) is not part of the override and not checked.
 */
final class DeployedOverrideGuard {

    private static final YAMLMapper YAML = YAMLMapper.builder().build();

    /** Services the override may add; everything else in services must exist in the shipped file. */
    private static final Set<String> ADDED_SERVICES = Set.of("proxy", "dovecot");
    /** Keys of an added service; volumes, ports and network aliases are checked further down. */
    private static final Set<String> ADDED_SERVICE_KEYS = Set.of("image", "pull_policy", "networks", "depends_on",
            "healthcheck", "sysctls", "environment", "command", "entrypoint", "user", "volumes", "ports");
    /** Network aliases an added service may claim; a shipped name (idp, mcp, keycloak, app) would hijack traffic. */
    private static final Map<String, Set<String>> ADDED_SERVICE_ALIASES = Map.of(
            "dovecot", Set.of("imap.imap-mcp.test"), "proxy", Set.of());
    private static final Pattern LOOPBACK_PORT = Pattern.compile("127\\.0\\.0\\.1::\\d+");
    private static final Set<String> TOP_LEVEL = Set.of("services", "networks");
    private static final Set<String> HEALTHCHECK_TIMING = Set.of("interval", "start_period", "retries", "timeout");
    /** Per shipped service: the keys beyond healthcheck timing (ports only as an empty reset, build only as null). */
    private static final Map<String, Set<String>> SERVICE_KEYS = Map.of(
            "app", Set.of("image", "pull_policy", "build", "ports", "healthcheck"),
            "caddy", Set.of("ports", "healthcheck"));
    private static final Set<String> DEFAULT_SERVICE_KEYS = Set.of("healthcheck");
    /** Shipped network keys the override may set, with the only permitted value. */
    private static final Map<String, Boolean> BACKEND_KEYS = Map.of("internal", true, "enable_ipv6", false);

    private DeployedOverrideGuard() {
    }

    /** The violations, each naming the offending path (e.g. {@code services.app.environment}); empty if allowed. */
    static List<String> violations(String shippedYaml, String overrideYaml) {
        JsonNode shipped = YAML.readTree(shippedYaml);
        JsonNode override = YAML.readTree(overrideYaml);
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, JsonNode> top : override.properties()) {
            if (!TOP_LEVEL.contains(top.getKey())) {
                out.add(top.getKey());
            }
        }
        for (Map.Entry<String, JsonNode> service : override.path("services").properties()) {
            checkService(service.getKey(), service.getValue(), shipped.path("services"), out);
        }
        for (Map.Entry<String, JsonNode> network : override.path("networks").properties()) {
            checkNetwork(network.getKey(), network.getValue(), shipped.path("networks"), out);
        }
        return out;
    }

    private static void checkService(String name, JsonNode node, JsonNode shippedServices, List<String> out) {
        String path = "services." + name;
        if (!shippedServices.has(name)) {
            if (!ADDED_SERVICES.contains(name)) {
                out.add(path);
            } else {
                checkAddedService(name, node, out);
            }
            return;
        }
        Set<String> allowed = SERVICE_KEYS.getOrDefault(name, DEFAULT_SERVICE_KEYS);
        for (Map.Entry<String, JsonNode> key : node.properties()) {
            String keyPath = path + "." + key.getKey();
            JsonNode value = key.getValue();
            if (!allowed.contains(key.getKey())) {
                out.add(keyPath);
            } else if (key.getKey().equals("ports") && !(value.isArray() && value.isEmpty())) {
                out.add(keyPath);
            } else if (key.getKey().equals("build") && !isNull(value)) {
                out.add(keyPath);
            } else if (key.getKey().equals("healthcheck")) {
                for (Map.Entry<String, JsonNode> hc : value.properties()) {
                    if (!HEALTHCHECK_TIMING.contains(hc.getKey())) {
                        out.add(keyPath + "." + hc.getKey());
                    }
                }
            }
        }
    }

    private static void checkAddedService(String name, JsonNode node, List<String> out) {
        String path = "services." + name;
        for (Map.Entry<String, JsonNode> key : node.properties()) {
            String keyPath = path + "." + key.getKey();
            JsonNode value = key.getValue();
            switch (key.getKey()) {
                case "volumes" -> {
                    // only bind mounts from the copy's directory (short form); never a named or absolute source
                    if (!value.isArray() || !allMatch(value, v -> v.isString() && v.asString().startsWith("./"))) {
                        out.add(keyPath);
                    }
                }
                case "ports" -> {
                    if (!name.equals("proxy") || !value.isArray()
                            || !allMatch(value, v -> v.isString() && LOOPBACK_PORT.matcher(v.asString()).matches())) {
                        out.add(keyPath);
                    }
                }
                case "networks" -> {
                    for (Map.Entry<String, JsonNode> network : value.isObject() ? value.properties()
                            : List.<Map.Entry<String, JsonNode>>of()) {
                        JsonNode aliases = network.getValue().path("aliases");
                        Set<String> allowed = ADDED_SERVICE_ALIASES.getOrDefault(name, Set.of());
                        if (!aliases.isMissingNode()
                                && (!aliases.isArray() || !allMatch(aliases, a -> a.isString()
                                        && allowed.contains(a.asString())))) {
                            out.add(keyPath + "." + network.getKey() + ".aliases");
                        }
                    }
                }
                default -> {
                    if (!ADDED_SERVICE_KEYS.contains(key.getKey())) {
                        out.add(keyPath);
                    }
                }
            }
        }
    }

    private static boolean allMatch(JsonNode array, java.util.function.Predicate<JsonNode> test) {
        for (JsonNode element : array) {
            if (!test.test(element)) {
                return false;
            }
        }
        return true;
    }

    /** Jackson reads the tagged scalar {@code !reset null} as the text "null"; a real null is the same reset. */
    private static boolean isNull(JsonNode value) {
        return value.isNull() || (value.isString() && value.asString().equals("null"));
    }

    private static void checkNetwork(String name, JsonNode node, JsonNode shippedNetworks, List<String> out) {
        String path = "networks." + name;
        if (!shippedNetworks.has(name)) {
            // a new network (the proxy's second one) must be plain: no internal, driver, ipam; shipped
            // services cannot attach to it because they may not set networks
            if (!node.isNull() && !node.isEmpty()) {
                out.add(path);
            }
            return;
        }
        for (Map.Entry<String, JsonNode> key : node.properties()) {
            Boolean required = name.equals("backend") ? BACKEND_KEYS.get(key.getKey()) : null;
            if (required == null || !key.getValue().isBoolean() || key.getValue().asBoolean() != required) {
                out.add(path + "." + key.getKey());
            }
        }
    }
}
