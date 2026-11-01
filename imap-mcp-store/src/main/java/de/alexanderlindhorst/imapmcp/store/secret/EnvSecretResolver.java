package de.alexanderlindhorst.imapmcp.store.secret;

import de.alexanderlindhorst.imapmcp.core.account.Secret;
import java.util.Map;
import java.util.regex.Pattern;

/** Resolves {@code env:NAME} against an injected environment map. */
final class EnvSecretResolver implements SecretResolver {

    private static final Pattern NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private final Map<String, String> env;

    EnvSecretResolver(Map<String, String> env) {
        this.env = Map.copyOf(env);
    }

    @Override
    public void validate(String name) {
        value(name);
    }

    @Override
    public Secret resolve(String name) {
        return new Secret(value(name).toCharArray());
    }

    private String value(String name) {
        if (!NAME.matcher(name).matches()) {
            throw new SecretResolutionException("env: reference needs a variable name [A-Za-z_][A-Za-z0-9_]*");
        }
        String value = env.get(name);
        if (value == null || value.isEmpty()) {
            throw new SecretResolutionException("environment variable " + name + " is not set or empty");
        }
        return value;
    }
}
