package io.github.consentgate.velocity;

import com.velocitypowered.api.command.SimpleCommand;
import io.github.consentgate.core.admin.AdminCommands;
import net.kyori.adventure.text.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

final class AdminCommand implements SimpleCommand {
    interface Operations extends AdminCommands.Operations { }
    private final AdminCommands commands;

    AdminCommand(Function<String, Optional<UUID>> onlinePlayer, Operations operations) {
        commands = new AdminCommands(onlinePlayer, operations);
    }

    @Override public void execute(Invocation invocation) {
        commands.execute(invocation.arguments(), invocation.source()::hasPermission,
                text -> invocation.source().sendMessage(Component.text(text)));
    }

    @Override public boolean hasPermission(Invocation invocation) {
        return AdminCommands.hasPermission(invocation.source()::hasPermission);
    }

    @Override public List<String> suggest(Invocation invocation) {
        return AdminCommands.suggest(invocation.arguments(), invocation.source()::hasPermission);
    }
}
