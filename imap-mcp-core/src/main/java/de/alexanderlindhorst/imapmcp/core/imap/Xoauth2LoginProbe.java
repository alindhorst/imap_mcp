package de.alexanderlindhorst.imapmcp.core.imap;

import de.alexanderlindhorst.imapmcp.core.account.MailAccount;

/**
 * The link-time probe for an upstream OAuth account (SPEC 5.3, 5.5): one {@code AUTHENTICATE XOAUTH2} with a given
 * address and access token, then CAPABILITY and LOGOUT, no folder, no retry and no renewal. Like {@link LoginProbe}
 * it runs through the pinned socket factory and never counts towards the failed-login counter.
 */
@FunctionalInterface
public interface Xoauth2LoginProbe {

    /**
     * {@code account.username()} is the address to authenticate; {@code account.auth()} is ignored (the probe always
     * uses XOAUTH2). A server that does not offer {@code AUTH=XOAUTH2} answers {@code AUTH_FAILED} without any
     * authentication command having been sent. The caller wipes {@code accessToken}.
     */
    LoginProbe.Result probeXoauth2(MailAccount account, char[] accessToken);
}
