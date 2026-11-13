package de.alexanderlindhorst.imapmcp.it;

/** XOAUTH2 smoke against a real Microsoft 365 account (#328); see {@link SmokeOAuthEnv} for the variables. */
class M365OAuthLiveSmoke extends AbstractOAuthLiveSmoke {

    @Override
    SmokeEnv.Provider provider() {
        return SmokeEnv.Provider.M365;
    }
}
