package de.alexanderlindhorst.imapmcp.core.tools.write;

import de.alexanderlindhorst.imapmcp.core.error.ToolError;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Structured output of a bulk write tool (SPEC 4, v2 write contract): {@code account}, {@code target_folder}
 * (not for set_flags), {@code summary}, {@code results} in input order and {@code notes}. Carries no
 * email-derived strings; handles are echoed only as given, folder names are identifiers. Item outcomes never
 * set MCP {@code isError}.
 */
public final class BulkResult {

    /** Closed {@code note} value of an item: the server reported no COPYUID. */
    public static final String NEW_UID_UNKNOWN = "new_uid_unknown";

    /** The nine fixed {@code notes} sentences, in the order the spec lists them. */
    public static final List<String> NOTES = List.of(
            "Some messages were already in the target folder and were left unchanged.",
            "Some messages were already in Trash and were left unchanged; permanent deletion is not available.",
            "Some messages are no longer in their source folder; if this call repeats an earlier one, they were already moved. "
                    + "Search the target folder for their new handles.",
            "The server did not report the new UIDs of some messages; search the target folder for their handles.",
            "Some messages no longer exist; re-run search_messages.",
            "Some folders changed since the handles were issued; re-run search_messages for those folders.",
            "The server refused the change for some messages; see the error of each item.",
            "The call stopped before some messages were processed; nothing was changed for them, so repeat the call with their handles.",
            "The outcome for some messages is unknown; check the source and target folders before repeating the call for them.");

    /** Closed {@code reason} of a failed purge item: the STORE succeeded but the message is still in the folder. */
    public static final String STILL_PRESENT = "still_present";

    /** {@code confirmation} values of a purge result. */
    public static final String ELICITED = "elicited";
    public static final String SESSION_APPROVAL = "session_approval";

    /** The seven {@code notes} sentences of purge_messages (SPEC 4 v3a), in spec order. */
    public static final List<String> PURGE_NOTES = List.of(
            "These messages are permanently removed from the mailbox; this server cannot restore them.",
            "Some messages were no longer in their folder; if this call repeats an earlier one, they were already removed.",
            NOTES.get(5),
            "Some messages could not be removed and may now be marked as deleted; set_flags with deleted false clears the mark.",
            NOTES.get(6),
            NOTES.get(7),
            "The outcome for some messages is unknown; repeating the call is safe because messages already removed are reported as already_gone.");

    /** Which bulk tool produced the result; decides target_folder, new_handle/note and the valid statuses. */
    public enum Kind {
        SET_FLAGS, MOVE, COPY, DELETE, PURGE;

        boolean hasTarget() {
            return this != SET_FLAGS && this != PURGE;
        }

        /** Whether items carry new_handle and note. */
        boolean hasNewHandle() {
            return hasTarget();
        }
    }

    /** One result item. {@code reason} only for stale_reference, {@code error} only for failed ({@code new_handle} there only for a copy that was not removed). */
    public record Item(String handle, ItemStatus status, String newHandle, String note,
            ToolError.StaleReason reason, ToolError error, String failReason) {

        public Item(String handle, ItemStatus status, String newHandle, String note, ToolError.StaleReason reason,
                ToolError error) {
            this(handle, status, newHandle, note, reason, error, null);
        }

        public Item {
            Objects.requireNonNull(handle, "handle");
            Objects.requireNonNull(status, "status");
            if ((reason != null) != (status == ItemStatus.STALE_REFERENCE)) {
                throw new IllegalArgumentException("reason is set exactly for stale_reference");
            }
            if ((error != null) != (status == ItemStatus.FAILED)) {
                throw new IllegalArgumentException("error is set exactly for failed");
            }
            if (note != null && !(NEW_UID_UNKNOWN.equals(note) && status == ItemStatus.OK && newHandle == null)) {
                throw new IllegalArgumentException("note is only new_uid_unknown on ok without new_handle");
            }
            if (newHandle != null && status != ItemStatus.OK && status != ItemStatus.ALREADY_IN_TARGET
                    && status != ItemStatus.FAILED) {
                throw new IllegalArgumentException("new_handle only for ok, already_in_target and failed");
            }
            if (failReason != null && !(STILL_PRESENT.equals(failReason) && status == ItemStatus.FAILED)) {
                throw new IllegalArgumentException("failReason is only still_present on failed");
            }
            if (status == ItemStatus.ALREADY_IN_TARGET && !handle.equals(newHandle)) {
                throw new IllegalArgumentException("already_in_target echoes the handle as new_handle");
            }
        }

        /** set_flags success. */
        public static Item okFlags(String handle) {
            return new Item(handle, ItemStatus.OK, null, null, null, null);
        }

        /** purge success: the UID is absent after the UID EXPUNGE. */
        public static Item okPurged(String handle) {
            return new Item(handle, ItemStatus.OK, null, null, null, null);
        }

        /** purge: the STORE of {@code \Deleted} succeeded, the message is still in the folder. */
        public static Item failedStillPresent(String handle, ToolError error) {
            return new Item(handle, ItemStatus.FAILED, null, null, null,
                    Objects.requireNonNull(error, "error"), STILL_PRESENT);
        }

        /** move/copy/delete success with the minted handle. */
        public static Item ok(String handle, String newHandle) {
            return new Item(handle, ItemStatus.OK, Objects.requireNonNull(newHandle, "newHandle"), null, null, null);
        }

        /** move/copy/delete success without COPYUID. */
        public static Item okNewUidUnknown(String handle) {
            return new Item(handle, ItemStatus.OK, null, NEW_UID_UNKNOWN, null, null);
        }

        public static Item alreadyInTarget(String handle) {
            return new Item(handle, ItemStatus.ALREADY_IN_TARGET, handle, null, null, null);
        }

        public static Item alreadyGone(String handle) {
            return new Item(handle, ItemStatus.ALREADY_GONE, null, null, null, null);
        }

        public static Item notFound(String handle) {
            return new Item(handle, ItemStatus.NOT_FOUND, null, null, null, null);
        }

        public static Item stale(String handle, ToolError.StaleReason reason) {
            return new Item(handle, ItemStatus.STALE_REFERENCE, null, null, Objects.requireNonNull(reason, "reason"), null);
        }

        public static Item failed(String handle, ToolError error) {
            return new Item(handle, ItemStatus.FAILED, null, null, null, Objects.requireNonNull(error, "error"));
        }

        /** move without MOVE: the copy exists under {@code newHandle} but the original was not removed. */
        public static Item failedCopied(String handle, String newHandle, ToolError error) {
            return new Item(handle, ItemStatus.FAILED, newHandle, null, null, Objects.requireNonNull(error, "error"));
        }

        public static Item notAttempted(String handle) {
            return new Item(handle, ItemStatus.NOT_ATTEMPTED, null, null, null, null);
        }

        public static Item unknown(String handle) {
            return new Item(handle, ItemStatus.UNKNOWN, null, null, null, null);
        }
    }

    private final Kind kind;
    private final String account;
    private final String targetFolder;
    private final List<Item> results;
    private final String confirmation;

    private BulkResult(Kind kind, String account, String targetFolder, List<Item> results, String confirmation) {
        this.kind = kind;
        this.account = account;
        this.targetFolder = targetFolder;
        this.results = results;
        this.confirmation = confirmation;
    }

    /** purge_messages result; {@code confirmation} is {@link #ELICITED} or {@link #SESSION_APPROVAL}. */
    public static BulkResult purge(String account, String confirmation, List<Item> results) {
        Objects.requireNonNull(account, "account");
        if (!ELICITED.equals(confirmation) && !SESSION_APPROVAL.equals(confirmation)) {
            throw new IllegalArgumentException("confirmation");
        }
        for (Item i : results) {
            validate(Kind.PURGE, i);
        }
        return new BulkResult(Kind.PURGE, account, null, List.copyOf(results), confirmation);
    }

    /** Like {@link #inInputOrder} for purge_messages. */
    public static BulkResult purgeInInputOrder(String account, String confirmation, List<String> inputHandles,
            List<Item> items) {
        return purge(account, confirmation, ordered(inputHandles, items));
    }

    /** Items must already be in input order. */
    public static BulkResult of(Kind kind, String account, String targetFolder, List<Item> results) {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(account, "account");
        if (kind.hasTarget() == (targetFolder == null)) {
            throw new IllegalArgumentException("target_folder is required exactly for tools other than set_flags");
        }
        for (Item i : results) {
            validate(kind, i);
        }
        return new BulkResult(kind, account, targetFolder, List.copyOf(results), null);
    }

    /** Orders the items (e.g. produced per folder group) by the input handles; each handle needs exactly one item. */
    public static BulkResult inInputOrder(Kind kind, String account, String targetFolder, List<String> inputHandles,
            List<Item> items) {
        return of(kind, account, targetFolder, ordered(inputHandles, items));
    }

    private static List<Item> ordered(List<String> inputHandles, List<Item> items) {
        Map<String, Item> byHandle = new HashMap<>();
        for (Item i : items) {
            if (byHandle.put(i.handle(), i) != null) {
                throw new IllegalArgumentException("duplicate item");
            }
        }
        if (byHandle.size() != inputHandles.size()) {
            throw new IllegalArgumentException("items do not match the input handles");
        }
        List<Item> ordered = new ArrayList<>(inputHandles.size());
        for (String h : inputHandles) {
            Item i = byHandle.get(h);
            if (i == null) {
                throw new IllegalArgumentException("items do not match the input handles");
            }
            ordered.add(i);
        }
        return ordered;
    }

    private static void validate(Kind kind, Item i) {
        boolean flags = !kind.hasNewHandle();
        boolean valid = switch (i.status()) {
            case OK -> flags ? i.newHandle() == null && i.note() == null : i.newHandle() != null || i.note() != null;
            case ALREADY_IN_TARGET -> kind == Kind.MOVE || kind == Kind.DELETE;
            case ALREADY_GONE -> kind == Kind.MOVE || kind == Kind.DELETE || kind == Kind.PURGE;
            case NOT_FOUND -> kind == Kind.SET_FLAGS || kind == Kind.COPY;
            default -> true;
        };
        if (!valid) {
            throw new IllegalArgumentException("item status " + i.status().wire() + " is not valid for " + kind);
        }
    }

    /** The purge {@code confirmation} value; null for the other tools. */
    public String confirmation() {
        return confirmation;
    }

    public Kind kind() {
        return kind;
    }

    public List<Item> results() {
        return results;
    }

    /** Count of each status among the results. */
    public Map<ItemStatus, Integer> counts() {
        Map<ItemStatus, Integer> c = new EnumMap<>(ItemStatus.class);
        for (ItemStatus s : ItemStatus.values()) {
            c.put(s, 0);
        }
        results.forEach(i -> c.merge(i.status(), 1, Integer::sum));
        return c;
    }

    /** The fixed sentences of the conditions that occurred at least once, in spec order. */
    public List<String> notes() {
        if (kind == Kind.PURGE) {
            return purgeNotes();
        }
        boolean[] occurred = new boolean[NOTES.size()];
        for (Item i : results) {
            switch (i.status()) {
                case ALREADY_IN_TARGET -> occurred[kind == Kind.DELETE ? 1 : 0] = true;
                case ALREADY_GONE -> occurred[2] = true;
                case OK -> occurred[3] |= NEW_UID_UNKNOWN.equals(i.note());
                case NOT_FOUND -> occurred[4] = true;
                case STALE_REFERENCE -> occurred[5] = true;
                case FAILED -> occurred[6] = true;
                case NOT_ATTEMPTED -> occurred[7] = true;
                case UNKNOWN -> occurred[8] = true;
            }
        }
        List<String> out = new ArrayList<>();
        for (int n = 0; n < occurred.length; n++) {
            if (occurred[n]) {
                out.add(NOTES.get(n));
            }
        }
        return out;
    }

    private List<String> purgeNotes() {
        boolean[] occurred = new boolean[PURGE_NOTES.size()];
        for (Item i : results) {
            switch (i.status()) {
                case OK -> occurred[0] = true;
                case ALREADY_GONE -> occurred[1] = true;
                case STALE_REFERENCE -> occurred[2] = true;
                case FAILED -> {
                    occurred[3] |= STILL_PRESENT.equals(i.failReason());
                    occurred[4] = true;
                }
                case NOT_ATTEMPTED -> occurred[5] = true;
                case UNKNOWN -> occurred[6] = true;
                default -> throw new IllegalStateException("status not valid for purge");
            }
        }
        List<String> out = new ArrayList<>();
        for (int n = 0; n < occurred.length; n++) {
            if (occurred[n]) {
                out.add(PURGE_NOTES.get(n));
            }
        }
        return out;
    }

    /** Wire shape in spec field order; every field present, inapplicable ones null (new_handle/note absent for set_flags). */
    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("account", account);
        if (kind == Kind.PURGE) {
            out.put("confirmation", confirmation);
        }
        if (kind.hasTarget()) {
            out.put("target_folder", targetFolder);
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("requested", results.size());
        counts().forEach((s, n) -> summary.put(s.wire(), n));
        out.put("summary", summary);
        List<Map<String, Object>> items = new ArrayList<>(results.size());
        for (Item i : results) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("handle", i.handle());
            m.put("status", i.status().wire());
            if (kind.hasNewHandle()) {
                m.put("new_handle", i.newHandle());
                m.put("note", i.note());
            }
            m.put("reason", i.reason() != null ? reasonWire(i.reason()) : i.failReason());
            m.put("error", i.error() == null ? null : i.error().toItemMap());
            items.add(m);
        }
        out.put("results", items);
        out.put("notes", notes());
        return out;
    }

    static String reasonWire(ToolError.StaleReason r) {
        return switch (r) {
            case UIDVALIDITY_CHANGED -> "uidvalidity_changed";
            case FOLDER_MISSING -> "folder_missing";
        };
    }
}
