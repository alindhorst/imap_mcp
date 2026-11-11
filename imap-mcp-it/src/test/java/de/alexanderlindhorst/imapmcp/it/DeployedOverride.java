package de.alexanderlindhorst.imapmcp.it;

/** The test-only compose override of the deployed stack (SPEC 5.8, allowlisted keys only). */
final class DeployedOverride {

    private DeployedOverride() {
    }

    static String render(String appImage, String proxyImage) {
        throw new UnsupportedOperationException();
    }
}
