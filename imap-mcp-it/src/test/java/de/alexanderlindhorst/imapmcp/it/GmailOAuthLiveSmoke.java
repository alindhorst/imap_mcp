package de.alexanderlindhorst.imapmcp.it;

/** XOAUTH2 smoke against a real Gmail account (#328); see {@link SmokeOAuthEnv} for the variables. */
class GmailOAuthLiveSmoke extends AbstractOAuthLiveSmoke {

    @Override
    SmokeEnv.Provider provider() {
        return SmokeEnv.Provider.GMAIL;
    }
}
