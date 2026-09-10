package io.github.consentgate.velocity;

import com.velocitypowered.api.command.SimpleCommand;
import net.kyori.adventure.text.Component;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;

final class AdminCommand implements SimpleCommand {
    private static final List<String> ACTIONS = List.of("status", "reset", "validate", "reload");
    interface Operations {
        void status(UUID playerId, Consumer<String> reply);
        void reset(UUID playerId, Consumer<String> reply);
        void configuration(boolean apply, Consumer<String> reply);
    }

    private final Function<String, Optional<UUID>> onlinePlayer;
    private final Operations operations;

    AdminCommand(Function<String, Optional<UUID>> onlinePlayer, Operations operations) {
        this.onlinePlayer = onlinePlayer;
        this.operations = operations;
    }

    @Override public void execute(Invocation invocation) {
        Consumer<String> reply = text -> invocation.source().sendMessage(Component.text(text));
        String[] args = invocation.arguments();
        String action = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        boolean configuration = action.equals("validate") || action.equals("reload");
        if (!ACTIONS.contains(action) || args.length != (configuration ? 1 : 2)) {
            reply.accept("Usage: /consentgate <status|reset> <online-player|uuid>, or /consentgate <validate|reload>");
            return;
        }
        if (!invocation.source().hasPermission("consentgate.admin." + action)) {
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

    @Override public boolean hasPermission(Invocation invocation) {
        return ACTIONS.stream().anyMatch(action -> invocation.source().hasPermission("consentgate.admin." + action));
    }

    @Override public List<String> suggest(Invocation invocation) {
        String[] args = invocation.arguments();
        if (args.length > 1) return List.of();
        String prefix = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        return ACTIONS.stream().filter(action -> action.startsWith(prefix)
                && invocation.source().hasPermission("consentgate.admin." + action)).toList();
    }
}
