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
    interface Operations {
        void status(UUID playerId, Consumer<String> reply);
        void reset(UUID playerId, Consumer<String> reply);
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
        if (args.length != 2 || !List.of("status", "reset").contains(args[0].toLowerCase(Locale.ROOT))) {
            reply.accept("Usage: /consentgate <status|reset> <online-player|uuid>");
            return;
        }
        String action = args[0].toLowerCase(Locale.ROOT);
        if (!invocation.source().hasPermission("consentgate.admin." + action)) {
            reply.accept("You do not have permission to use this command.");
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
        return invocation.source().hasPermission("consentgate.admin.status")
                || invocation.source().hasPermission("consentgate.admin.reset");
    }

    @Override public List<String> suggest(Invocation invocation) {
        String[] args = invocation.arguments();
        if (args.length > 1) return List.of();
        String prefix = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        return List.of("status", "reset").stream().filter(action -> action.startsWith(prefix)
                && invocation.source().hasPermission("consentgate.admin." + action)).toList();
    }
}
