package io.github.consentgate.core.admin;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/** Shared command rules; platforms supply identity, permission checks, and reply delivery. */
public final class AdminCommands {
    private static final List<String> ACTIONS = List.of("status", "reset", "validate", "reload", "preview", "document");

    public interface Operations {
        void status(UUID playerId, Consumer<String> reply);
        void reset(UUID playerId, Consumer<String> reply);
        void configuration(boolean apply, Consumer<String> reply);
        void preview(UUID playerId, String locale, Consumer<String> reply);
        void document(String[] args, Consumer<String> reply);
    }

    private final Function<String, Optional<UUID>> onlinePlayer;
    private final Operations operations;

    public AdminCommands(Function<String, Optional<UUID>> onlinePlayer, Operations operations) {
        this.onlinePlayer = onlinePlayer;
        this.operations = operations;
    }

    public void execute(String[] args, Predicate<String> permission, Consumer<String> reply) {
        String action = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        boolean configuration = action.equals("validate") || action.equals("reload");
        boolean valid = switch (action) {
            case "validate", "reload" -> args.length == 1;
            case "status", "reset" -> args.length == 2;
            case "preview" -> args.length == 2 || args.length == 3;
            case "document" -> args.length >= 1 && args.length <= 4;
            default -> false;
        };
        if (!valid) {
            reply.accept("Usage: /consentgate <status|reset> <online-player|uuid>, <validate|reload>, preview <uuid> [locale|cancel], or document [id] [locale] [page]");
            return;
        }
        if (!permission.test("consentgate.admin." + action)) {
            reply.accept("You do not have permission to use this command.");
            return;
        }
        if (configuration) {
            operations.configuration(action.equals("reload"), reply);
            return;
        }
        if (action.equals("document")) {
            operations.document(java.util.Arrays.copyOfRange(args, 1, args.length), reply);
            return;
        }
        Optional<UUID> target = onlinePlayer.apply(args[1]);
        if (target.isEmpty()) {
            try {
                UUID parsed = UUID.fromString(args[1]);
                if (parsed.toString().equalsIgnoreCase(args[1])) target = Optional.of(parsed);
            } catch (IllegalArgumentException ignored) { }
        }
        if (target.isEmpty()) {
            reply.accept("Player not found online. For an offline player, use their full UUID from the consent records.");
            return;
        }
        if (action.equals("status")) operations.status(target.orElseThrow(), reply);
        else if (action.equals("reset")) operations.reset(target.orElseThrow(), reply);
        else operations.preview(target.orElseThrow(), args.length == 3 ? args[2] : null, reply);
    }

    public static boolean hasPermission(Predicate<String> permission) {
        return ACTIONS.stream().anyMatch(action -> permission.test("consentgate.admin." + action));
    }

    public static List<String> suggest(String[] args, Predicate<String> permission) {
        if (args.length > 1) return List.of();
        String prefix = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        return ACTIONS.stream().filter(action -> action.startsWith(prefix)
                && permission.test("consentgate.admin." + action)).toList();
    }
}
