-- MailAccount.address is optional (SPEC 5.3 list_accounts), so a linked account may have none.
ALTER TABLE mailbox_account ALTER COLUMN address DROP NOT NULL;
