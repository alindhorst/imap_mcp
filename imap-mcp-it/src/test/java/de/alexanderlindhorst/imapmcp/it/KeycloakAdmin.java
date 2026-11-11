package de.alexanderlindhorst.imapmcp.it;

/** Creates test users in the deployed realm with kcadm.sh inside the keycloak container. */
final class KeycloakAdmin {

    /** A realm user; the password is generated per run and never part of {@code toString}. */
    record TestUser(String username, String password) {
        @Override
        public String toString() {
            return "TestUser[" + username + "]";
        }
    }

    KeycloakAdmin(DeployedStack stack) {
    }

    TestUser createUser() throws Exception {
        throw new UnsupportedOperationException();
    }

    /** The realm's {@code accessTokenLifespan} in seconds. */
    int accessTokenLifespan() throws Exception {
        throw new UnsupportedOperationException();
    }
}
