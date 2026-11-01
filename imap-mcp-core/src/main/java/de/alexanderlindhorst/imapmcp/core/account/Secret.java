package de.alexanderlindhorst.imapmcp.core.account;

import java.util.Arrays;
import java.util.Objects;

/** Resolved secret material, held only for the duration of a connect. Wipe with {@link #close()}. */
public final class Secret implements AutoCloseable {

    private final char[] material;

    public Secret(char[] material) {
        this.material = Objects.requireNonNull(material, "material");
    }

    /** The live array, not a copy; callers must not retain it. */
    public char[] chars() {
        return material;
    }

    @Override
    public void close() {
        Arrays.fill(material, '\0');
    }

    @Override
    public String toString() {
        return "Secret[***]";
    }
}
