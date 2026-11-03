package de.alexanderlindhorst.imapmcp.core.tools.write;

/** Stub so the test compiles; replaced by the implementation commit. */
public final class UidCommands {

    public enum FlagMode { ADD, REMOVE }

    public record Result(org.eclipse.angus.mail.iap.Response tagged, org.eclipse.angus.mail.iap.Response[] responses) {
        public boolean ok() { return false; }
        public boolean no() { return false; }
        public java.util.Optional<CopyUidParser.CopyUid> copyUid() { return java.util.Optional.empty(); }
    }

    public static Result uidMove(org.eclipse.angus.mail.imap.IMAPFolder f, java.util.Collection<Long> u, String t) { throw new UnsupportedOperationException(); }
    public static Result uidCopy(org.eclipse.angus.mail.imap.IMAPFolder f, java.util.Collection<Long> u, String t) { throw new UnsupportedOperationException(); }
    public static Result uidStore(org.eclipse.angus.mail.imap.IMAPFolder f, java.util.Collection<Long> u, FlagMode m, jakarta.mail.Flags fl) { throw new UnsupportedOperationException(); }
    public static Result uidExpunge(org.eclipse.angus.mail.imap.IMAPFolder f, java.util.Collection<Long> u) { throw new UnsupportedOperationException(); }
    public static Result create(org.eclipse.angus.mail.imap.IMAPFolder f, String n) { throw new UnsupportedOperationException(); }
    public static Result subscribe(org.eclipse.angus.mail.imap.IMAPFolder f, String n) { throw new UnsupportedOperationException(); }
}
