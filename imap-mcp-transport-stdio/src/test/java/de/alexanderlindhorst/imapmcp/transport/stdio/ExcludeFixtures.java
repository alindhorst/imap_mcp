package de.alexanderlindhorst.imapmcp.transport.stdio;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.core.importer.Location;

/** Keeps the deliberately violating fixtures out of the real architecture scan. */
public final class ExcludeFixtures implements ImportOption {
    @Override
    public boolean includes(Location location) {
        return !location.contains("/stdio/archfixture/");
    }
}
