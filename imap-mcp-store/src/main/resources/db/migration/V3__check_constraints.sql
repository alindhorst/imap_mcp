-- CHECK constraints for values the code writes and reads (Q1 review notes). Conventions as stored today:
-- security and flavor are the enum names (MailAccount.Security, MailAccount.Flavor), auth is lower case.
ALTER TABLE tenant ADD CONSTRAINT tenant_id_not_nil CHECK (id <> '00000000-0000-0000-0000-000000000000'::uuid);
ALTER TABLE mailbox_account
    ADD CONSTRAINT mailbox_account_security_valid CHECK (security IN ('IMPLICIT_TLS', 'STARTTLS')),
    ADD CONSTRAINT mailbox_account_flavor_valid CHECK (flavor IN ('GENERIC', 'GMAIL')),
    ADD CONSTRAINT mailbox_account_auth_valid CHECK (auth IN ('password', 'xoauth2'));
