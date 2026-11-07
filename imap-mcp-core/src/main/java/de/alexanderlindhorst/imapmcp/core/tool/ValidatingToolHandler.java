package de.alexanderlindhorst.imapmcp.core.tool;

import de.alexanderlindhorst.imapmcp.core.error.ToolError;
import java.util.Map;
import java.util.Objects;

/**
 * Decorator that validates arguments against the tool's input schema before the wrapped handler runs.
 * Invalid input yields a {@code Failure(INVALID_ARGUMENT)}; the delegate then never sees the call. The
 * delegate receives the null-free argument copy (JSON null = absent).
 */
public final class ValidatingToolHandler implements ToolHandler {

    private final InputValidator validator;
    private final ToolHandler delegate;

    public ValidatingToolHandler(ToolDefinition definition, ToolHandler delegate) {
        this.validator = new InputValidator(definition.inputSchema());
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public ToolOutcome handle(ToolCallContext context, Map<String, Object> args) {
        InputValidator.Result r = validator.validate(args, context.requestId());
        if (!r.valid()) {
            context.rejectArguments();
            return new ToolOutcome.Failure(r.error());
        }
        return delegate.handle(context, r.args());
    }
}
