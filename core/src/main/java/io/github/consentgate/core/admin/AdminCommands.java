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
    private static final List<String> ACTIONS = List.of("status", "reset", "validate", "reload");

    public interface Operations {
        void status(UUID playerId, Consumer<String> reply);
        void reset(UUID playerId, Consumer<String> reply);
        void configuration(boolean apply, Consumer<String> reply);
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
        if (!ACTIONS.contains(action) || args.length != (configuration ? 1 : 2)) {
            reply.accept("Usage: /consentgate <status|reset> <online-player|uuid>, or /consentgate <validate|reload>");
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
        else operations.reset(target.orElseThrow(), reply);
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
