package de.alexanderlindhorst.imapmcp.core.audit;

import java.util.ArrayList;
import java.util.List;

/** Test double: records the order of begin/complete/event and can fail on demand. */
public final class RecordingAuditLog implements AuditLog {

    public final List<String> order = new ArrayList<>();
    public final List<CallInfo> calls = new ArrayList<>();
    public final List<List<ItemRef>> begun = new ArrayList<>();
    public final List<Completion> completions = new ArrayList<>();
    public final List<EventInfo> events = new ArrayList<>();
    public boolean failBegin;
    public boolean failComplete;
    public boolean failEvent;

    @Override
    public Entry begin(CallInfo call, List<ItemRef> items) {
        if (failBegin) {
            throw new AuditWriteException();
        }
        order.add("begin");
        calls.add(call);
        begun.add(List.copyOf(items));
        return completion -> {
            if (failComplete) {
                throw new AuditWriteException();
            }
            order.add("complete");
            completions.add(completion);
        };
    }

    @Override
    public void event(EventInfo event) {
        if (failEvent) {
            throw new AuditWriteException();
        }
        events.add(event);
    }
}
