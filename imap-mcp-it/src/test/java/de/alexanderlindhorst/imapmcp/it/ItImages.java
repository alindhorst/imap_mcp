package de.alexanderlindhorst.imapmcp.it;

/** Container images, referenced by tag and digest only (pinned versions, SPEC 5.7). */
public final class ItImages {

    public static final String DOVECOT =
            "dovecot/dovecot:2.4.5@sha256:43c14def08a57f25107593cbcf64a0a6fbae84a7b9de7aa038513c9b21e91914";
    public static final String TOXIPROXY =
            "ghcr.io/shopify/toxiproxy:2.12.0@sha256:9378ed52a28bc50edc1350f936f518f31fa95f0d15917d6eb40b8e376d1a214e";

    public static final String POSTGRES =
            "postgres:17-alpine@sha256:3540c0362c809df084ffac0ae69fe794515f9de92692f02178bcdce0ef0302ac";

    public static final String KEYCLOAK =
            "quay.io/keycloak/keycloak:26.4@sha256:9409c59bdfb65dbffa20b11e6f18b8abb9281d480c7ca402f51ed3d5977e6007";

    public static final String VAULT =
            "hashicorp/vault:1.20@sha256:268bb80aa9c6d13d65fcfa05c0c268caca068952240a8087291a6ce0b66e3a10";

    private ItImages() {
    }
}
