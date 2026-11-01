package de.alexanderlindhorst.imapmcp.core.tools;

import java.util.LinkedHashMap;
import java.util.Map;

/** Insertion-ordered schema maps (the input validator depends on the declaration order). */
final class Schemas {

    private Schemas() {
    }

    static Map<String, Object> of(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }
}
