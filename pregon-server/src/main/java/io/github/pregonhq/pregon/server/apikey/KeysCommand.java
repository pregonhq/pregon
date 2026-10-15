package io.github.pregonhq.pregon.server.apikey;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import io.github.pregonhq.pregon.core.apikey.ApiKeyScope;
import io.github.pregonhq.pregon.core.apikey.ApiKeyToken;

/**
 * {@code pregon keys ...}: manages API keys from inside the server container (ADR-0018).
 *
 * <pre>
 * pregon keys create --scope admin|ingest [--scope ...] --name TEXT [--tenant ID]
 * pregon keys list [--tenant ID]
 * pregon keys revoke ID [--tenant ID]
 * </pre>
 *
 * The full key is printed once, on standard output, and never logged.
 */
public final class KeysCommand {

    static final String DEFAULT_TENANT = "default";

    private final ApiKeys apiKeys;
    private final PrintStream out;
    private final PrintStream err;

    public KeysCommand(ApiKeys apiKeys, PrintStream out, PrintStream err) {
        this.apiKeys = apiKeys;
        this.out = out;
        this.err = err;
    }

    /** Runs the command after {@code keys}; returns the process exit code. */
    public int run(List<String> args) {
        if (args.isEmpty()) {
            return usage();
        }
        try {
            return switch (args.getFirst()) {
                case "create" -> create(args.subList(1, args.size()));
                case "list" -> list(args.subList(1, args.size()));
                case "revoke" -> revoke(args.subList(1, args.size()));
                default -> usage();
            };
        } catch (IllegalArgumentException invalid) {
            err.println("pregon keys: " + invalid.getMessage());
            return 2;
        }
    }

    private int create(List<String> args) {
        Set<ApiKeyScope> scopes = EnumSet.noneOf(ApiKeyScope.class);
        String name = null;
        String tenant = DEFAULT_TENANT;
        for (int i = 0; i < args.size(); i++) {
            switch (args.get(i)) {
                case "--scope" -> scopes.add(scope(value(args, ++i, "--scope")));
                case "--name" -> name = value(args, ++i, "--name");
                case "--tenant" -> tenant = value(args, ++i, "--tenant");
                default -> throw new IllegalArgumentException("unknown option " + args.get(i));
            }
        }
        if (scopes.isEmpty() || name == null) {
            throw new IllegalArgumentException("create needs --scope and --name");
        }
        ApiKeyToken token = apiKeys.create(tenant, name, scopes);
        out.println(token.value());
        err.println("Created key " + token.id() + " for tenant " + tenant + " with scopes " + scopes
                + ". Store it now: it cannot be shown again.");
        return 0;
    }

    private int list(List<String> args) {
        String tenant = tenantOption(args, 0);
        for (ApiKeys.Summary key : apiKeys.list(tenant)) {
            out.printf("%s\t%s\t%s\tcreated %s\tlast used %s%s%n", key.id(), key.name(), key.scopes(), key.createdAt(),
                    key.lastUsedAt() == null ? "never" : key.lastUsedAt(),
                    key.revokedAt() == null ? "" : "\trevoked " + key.revokedAt());
        }
        return 0;
    }

    private int revoke(List<String> args) {
        if (args.isEmpty()) {
            throw new IllegalArgumentException("revoke needs a key id");
        }
        String tenant = tenantOption(args, 1);
        if (!apiKeys.revoke(tenant, args.getFirst())) {
            err.println("pregon keys: no key " + args.getFirst() + " in tenant " + tenant);
            return 1;
        }
        err.println("Revoked key " + args.getFirst() + ".");
        return 0;
    }

    private int usage() {
        err.println("""
                usage: pregon keys create --scope admin|ingest [--scope ...] --name TEXT [--tenant ID]
                       pregon keys list [--tenant ID]
                       pregon keys revoke ID [--tenant ID]""");
        return 2;
    }

    private static String tenantOption(List<String> args, int from) {
        List<String> rest = new ArrayList<>(args.subList(Math.min(from, args.size()), args.size()));
        if (rest.isEmpty()) {
            return DEFAULT_TENANT;
        }
        if (rest.size() == 2 && rest.getFirst().equals("--tenant")) {
            return rest.get(1);
        }
        throw new IllegalArgumentException("unexpected arguments " + rest);
    }

    private static ApiKeyScope scope(String text) {
        try {
            return ApiKeyScope.valueOf(text.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw new IllegalArgumentException("unknown scope " + text + " (use admin or ingest)");
        }
    }

    private static String value(List<String> args, int index, String option) {
        if (index >= args.size()) {
            throw new IllegalArgumentException(option + " needs a value");
        }
        return args.get(index);
    }
}
