package de.alexanderlindhorst.imapmcp.core.tool;

import de.alexanderlindhorst.imapmcp.core.error.ToolError;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Validates tool arguments against the very input schema the tool publishes, restricted to the JSON Schema
 * subset our tools use: type (string, integer, number, boolean, array, object), properties, required,
 * additionalProperties (only {@code false}), minLength/maxLength, minimum/maximum, minItems/maxItems, uniqueItems, items,
 * enum, pattern, format (date, date-time with offset). Any other keyword fails at construction.
 *
 * <p>Unknown properties are always rejected (object schemas default to {@code additionalProperties:false}).
 * JSON {@code null} on a property counts as absent and is removed from the returned copy. Out-of-bounds values
 * are rejected, never clamped. Error values are never echoed.
 *
 * <p>Only the first violation is reported, in a deterministic order per object: unknown properties (sorted by
 * name), then {@code required} in schema order, then declared properties in the schema's {@code properties}
 * iteration order (use a LinkedHashMap when declaring a schema), recursing into nested values.
 * Mapping: unknown_property, required, type, out_of_range (bounds, items count, enum; {@code max} only for
 * an upper bound), format (pattern, format), duplicate (uniqueItems, reported at the second occurrence).
 */
public final class InputValidator {

    private static final Set<String> ANNOTATIONS = Set.of("$schema", "title", "description", "default", "examples");
    private static final Set<String> TYPES = Set.of("string", "integer", "number", "boolean", "array", "object");
    private static final Set<String> FORMATS = Set.of("date", "date-time");

    private final Map<String, Object> schema;

    public InputValidator(Map<String, Object> schema) {
        this.schema = schema;
        checkSchema(schema, "$");
        if (!"object".equals(schema.get("type"))) {
            throw new IllegalArgumentException("input schema root must have type object");
        }
    }

    /** Valid: {@code args} is a null-free copy. Invalid: {@code error} is the INVALID_ARGUMENT result. */
    public record Result(Map<String, Object> args, ToolError error) {
        public boolean valid() {
            return error == null;
        }
    }

    private record Violation(String field, String reason, Integer max) {
    }

    // ---- definition-time check ----

    @SuppressWarnings("unchecked")
    private static void checkSchema(Map<String, Object> s, String where) {
        for (Map.Entry<String, Object> e : s.entrySet()) {
            String k = e.getKey();
            Object v = e.getValue();
            switch (k) {
                case "type" -> {
                    if (!(v instanceof String t) || !TYPES.contains(t)) {
                        throw new IllegalArgumentException("unsupported type at " + where + ": " + v);
                    }
                }
                case "required" -> {
                    if (!(v instanceof List<?>)) {
                        throw new IllegalArgumentException("required must be a list at " + where);
                    }
                }
                case "additionalProperties" -> {
                    if (!Boolean.FALSE.equals(v)) {
                        throw new IllegalArgumentException("only additionalProperties:false is supported at " + where);
                    }
                }
                case "minLength", "maxLength", "minItems", "maxItems", "minimum", "maximum", "enum" -> {
                }
                case "uniqueItems" -> {
                    if (!(v instanceof Boolean)) {
                        throw new IllegalArgumentException("uniqueItems must be a boolean at " + where);
                    }
                }
                case "pattern" -> {
                    try {
                        EcmaPattern.check((String) v);
                        Pattern.compile((String) v);
                    } catch (PatternSyntaxException | ClassCastException ex) {
                        throw new IllegalArgumentException("invalid pattern at " + where);
                    }
                }
                case "format" -> {
                    if (!FORMATS.contains(v)) {
                        throw new IllegalArgumentException("unsupported format at " + where + ": " + v);
                    }
                }
                case "properties" -> {
                    for (Map.Entry<String, Object> p : ((Map<String, Object>) v).entrySet()) {
                        checkSchema((Map<String, Object>) p.getValue(), where + "." + p.getKey());
                    }
                }
                case "items" -> checkSchema((Map<String, Object>) v, where + "[]");
                default -> {
                    if (!ANNOTATIONS.contains(k)) {
                        throw new IllegalArgumentException("unsupported schema keyword at " + where + ": " + k);
                    }
                }
            }
        }
    }

    // ---- validation ----

    public Result validate(Map<String, Object> args, String requestId) {
        Map<String, Object> cleaned = new LinkedHashMap<>();
        Violation v = object(schema, args, "", cleaned);
        if (v != null) {
            return new Result(null, ToolError.invalidArgument(v.field, v.reason, v.max, requestId));
        }
        return new Result(Collections.unmodifiableMap(cleaned), null);
    }

    @SuppressWarnings("unchecked")
    private static Violation object(Map<String, Object> s, Map<String, Object> value, String path, Map<String, Object> out) {
        Map<String, Object> props = (Map<String, Object>) s.getOrDefault("properties", Map.of());
        for (String name : new TreeSet<>(value.keySet())) {
            if (!props.containsKey(name)) {
                return new Violation(path + name, "unknown_property", null);
            }
        }
        for (Object r : (List<Object>) s.getOrDefault("required", List.of())) {
            if (value.get((String) r) == null) {
                return new Violation(path + r, "required", null);
            }
        }
        for (Map.Entry<String, Object> p : props.entrySet()) {
            Object v = value.get(p.getKey());
            if (v == null) {
                continue; // absent or JSON null
            }
            Object[] cleaned = new Object[1];
            Violation viol = node((Map<String, Object>) p.getValue(), v, path + p.getKey(), cleaned);
            if (viol != null) {
                return viol;
            }
            out.put(p.getKey(), cleaned[0]);
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Violation node(Map<String, Object> s, Object v, String path, Object[] cleaned) {
        cleaned[0] = v;
        String type = (String) s.get("type");
        if (type != null) {
            Violation t = checkType(type, v, path);
            if (t != null) {
                return t;
            }
        }
        if (s.containsKey("enum") && !((List<Object>) s.get("enum")).contains(v)) {
            return new Violation(path, "out_of_range", null);
        }
        if (v instanceof String str) {
            return string(s, str, path);
        }
        if (v instanceof Number n && !(v instanceof Boolean)) {
            return number(s, n, path);
        }
        if (v instanceof List<?> list) {
            return array(s, (List<Object>) list, path, cleaned);
        }
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            Violation o = object(s, (Map<String, Object>) m, path + ".", out);
            cleaned[0] = Collections.unmodifiableMap(out);
            return o;
        }
        return null;
    }

    private static Violation checkType(String type, Object v, String path) {
        boolean ok = switch (type) {
            case "string" -> v instanceof String;
            case "boolean" -> v instanceof Boolean;
            case "integer" -> v instanceof Number n && isIntegral(n);
            case "number" -> v instanceof Number;
            case "array" -> v instanceof List<?>;
            default -> v instanceof Map<?, ?>;
        };
        return ok ? null : new Violation(path, "type", null);
    }

    private static boolean isIntegral(Number n) {
        if (n instanceof Double || n instanceof Float) {
            double d = n.doubleValue();
            return !Double.isNaN(d) && !Double.isInfinite(d) && d == Math.rint(d);
        }
        return !(n instanceof BigDecimal bd) || bd.stripTrailingZeros().scale() <= 0;
    }

    private static Violation string(Map<String, Object> s, String str, String path) {
        int len = str.codePointCount(0, str.length());
        if (s.get("maxLength") instanceof Number max && len > max.intValue()) {
            return new Violation(path, "out_of_range", max.intValue());
        }
        if (s.get("minLength") instanceof Number min && len < min.intValue()) {
            return new Violation(path, "out_of_range", null);
        }
        if (s.get("pattern") instanceof String p && !Pattern.compile(p).matcher(str).matches()) {
            return new Violation(path, "format", null);
        }
        if (s.get("format") instanceof String f) {
            try {
                if (f.equals("date")) {
                    LocalDate.parse(str);
                } else {
                    OffsetDateTime.parse(str);
                }
            } catch (DateTimeParseException e) {
                return new Violation(path, "format", null);
            }
        }
        return null;
    }

    private static Violation number(Map<String, Object> s, Number n, String path) {
        BigDecimal v = new BigDecimal(n.toString());
        if (s.get("maximum") instanceof Number max && v.compareTo(new BigDecimal(max.toString())) > 0) {
            return new Violation(path, "out_of_range", max.intValue());
        }
        if (s.get("minimum") instanceof Number min && v.compareTo(new BigDecimal(min.toString())) < 0) {
            return new Violation(path, "out_of_range", null);
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Violation array(Map<String, Object> s, List<Object> list, String path, Object[] cleaned) {
        if (s.get("maxItems") instanceof Number max && list.size() > max.intValue()) {
            return new Violation(path, "out_of_range", max.intValue());
        }
        if (s.get("minItems") instanceof Number min && list.size() < min.intValue()) {
            return new Violation(path, "out_of_range", null);
        }
        if (s.get("items") instanceof Map<?, ?> items) {
            List<Object> out = new ArrayList<>(list.size());
            for (int i = 0; i < list.size(); i++) {
                Object[] c = new Object[1];
                Violation v = node((Map<String, Object>) items, list.get(i), path + "[" + i + "]", c);
                if (v != null) {
                    return v;
                }
                out.add(c[0]);
            }
            cleaned[0] = Collections.unmodifiableList(out);
        }
        if (Boolean.TRUE.equals(s.get("uniqueItems"))) {
            Set<Object> seen = new java.util.HashSet<>();
            for (int i = 0; i < list.size(); i++) {
                if (!seen.add(canonical(list.get(i)))) {
                    return new Violation(path + "[" + i + "]", "duplicate", null);
                }
            }
        }
        return null;
    }

    /** Normalizes parsed JSON so that equal JSON values (e.g. 1 and 1.0) compare equal. */
    private static Object canonical(Object v) {
        if (v instanceof Number n) {
            try {
                BigDecimal d = new BigDecimal(n.toString()).stripTrailingZeros();
                return d.signum() == 0 ? BigDecimal.ZERO : d;
            } catch (NumberFormatException e) {
                return n;
            }
        }
        if (v instanceof List<?> l) {
            List<Object> out = new ArrayList<>(l.size());
            l.forEach(x -> out.add(canonical(x)));
            return out;
        }
        if (v instanceof Map<?, ?> m) {
            Map<Object, Object> out = new java.util.HashMap<>();
            m.forEach((k, x) -> out.put(k, canonical(x)));
            return out;
        }
        return v;
    }
}
