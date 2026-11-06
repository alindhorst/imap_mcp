package de.alexanderlindhorst.imapmcp.core.linking;

/** The settings form of an account: label and the two permission flags. */
public record SettingsRequest(String label, boolean readOnly, boolean allowPurge) {

    @Override
    public String toString() {
        return "SettingsRequest[readOnly=" + readOnly + ", allowPurge=" + allowPurge + "]";
    }
}
