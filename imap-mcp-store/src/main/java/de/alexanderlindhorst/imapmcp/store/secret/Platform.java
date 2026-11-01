package de.alexanderlindhorst.imapmcp.store.secret;

import java.util.Locale;

/** Operating system family, relevant for choosing the keyring CLI. */
public enum Platform {
    LINUX,
    MACOS,
    WINDOWS,
    OTHER;

    /** Maps an {@code os.name} value; the caller passes it in so tests need no system property. */
    public static Platform fromOsName(String osName) {
        String name = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        if (name.contains("linux")) {
            return LINUX;
        }
        if (name.contains("mac") || name.contains("darwin")) {
            return MACOS;
        }
        if (name.contains("win")) {
            return WINDOWS;
        }
        return OTHER;
    }

    public static Platform current() {
        return fromOsName(System.getProperty("os.name"));
    }
}
