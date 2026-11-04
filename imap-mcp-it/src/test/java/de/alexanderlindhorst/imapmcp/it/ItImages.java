package de.alexanderlindhorst.imapmcp.it;

/** Container images, referenced by tag and digest only (pinned versions, SPEC 5.7). */
public final class ItImages {

    public static final String DOVECOT =
            "dovecot/dovecot:2.4.5@sha256:43c14def08a57f25107593cbcf64a0a6fbae84a7b9de7aa038513c9b21e91914";
    public static final String TOXIPROXY =
            "ghcr.io/shopify/toxiproxy:2.12.0@sha256:9378ed52a28bc50edc1350f936f518f31fa95f0d15917d6eb40b8e376d1a214e";

    private ItImages() {
    }
}
