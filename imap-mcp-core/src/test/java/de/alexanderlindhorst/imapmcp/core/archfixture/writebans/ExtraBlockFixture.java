package de.alexanderlindhorst.imapmcp.core.archfixture.writebans;

import de.alexanderlindhorst.imapmcp.core.tool.ToolOutcome;
import java.util.List;
import java.util.Map;

/** Deliberately violates the extra-content-block rule; excluded from the real scan. */
public final class ExtraBlockFixture {

    public ToolOutcome produce() {
        ToolOutcome.ContentBlock block = new ToolOutcome.EmbeddedResource("u", "text/plain", new byte[0]);
        return new ToolOutcome.Success(Map.of(), List.of(block));
    }
}
