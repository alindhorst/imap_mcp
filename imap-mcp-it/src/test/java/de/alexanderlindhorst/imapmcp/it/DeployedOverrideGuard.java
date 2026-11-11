package de.alexanderlindhorst.imapmcp.it;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
