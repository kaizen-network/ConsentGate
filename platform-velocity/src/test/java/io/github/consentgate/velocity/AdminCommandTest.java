package io.github.consentgate.velocity;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class AdminCommandTest {
    private final UUID player = UUID.randomUUID();
    private final List<String> calls = new ArrayList<>();
    private final AdminCommand command = new AdminCommand(
            name -> name.equals("TestPlayer") ? Optional.of(player) : Optional.empty(), new AdminCommand.Operations() {
                public void status(UUID id, Consumer<String> reply) { calls.add("status:" + id); }
                public void reset(UUID id, Consumer<String> reply) { calls.add("reset:" + id); }
                public void configuration(boolean apply, Consumer<String> reply) { calls.add(apply ? "reload" : "validate"); }
            });

    @Test void resolvesOnlineNamesAndCanonicalOfflineUuids() {
        command.execute(invocation(Set.of("status"), "status", "TestPlayer"));
        command.execute(invocation(Set.of("reset"), "RESET", player.toString().toUpperCase(java.util.Locale.ROOT)));
        assertEquals(List.of("status:" + player, "reset:" + player), calls);
    }

    @Test void rejectsUnknownNamesShortUuidsAndBadArgumentCounts() {
        for (String target : List.of("UnknownPlayer", "1-1-1-1-1", "not-a-uuid")) {
            command.execute(invocation(Set.of("reset"), "reset", target));
        }
        command.execute(invocation(Set.of("reset"), "reset"));
        command.execute(invocation(Set.of("reset"), "reset", player.toString(), "extra"));
        assertTrue(calls.isEmpty());
    }

    @Test void statusPermissionDoesNotAllowReset() {
        var invocation = invocation(Set.of("status"), "reset", player.toString());
        assertTrue(command.hasPermission(invocation));
        command.execute(invocation);
        assertTrue(calls.isEmpty());
        assertFalse(command.hasPermission(invocation(Set.of(), "status", player.toString())));
        command.execute(invocation(Set.of(), "status", player.toString()));
        assertTrue(calls.isEmpty());
    }

    @Test void suggestionsRespectPermissionsAndDoNotExposePlayers() {
        assertEquals(List.of("status"), command.suggest(invocation(Set.of("status"), "")));
        assertEquals(List.of("reset"), command.suggest(invocation(Set.of("reset"), "r")));
        assertTrue(command.suggest(invocation(Set.of("status"), "status", "")).isEmpty());
    }

    @Test void configurationActionsNeedTheirOwnPermissionAndNoTarget() {
        command.execute(invocation(Set.of("validate"), "validate"));
        command.execute(invocation(Set.of("reload"), "reload"));
        assertEquals(List.of("validate", "reload"), calls);
        command.execute(invocation(Set.of("status", "reset"), "reload"));
        command.execute(invocation(Set.of("validate"), "reload"));
        command.execute(invocation(Set.of("reload"), "reload", "extra"));
        assertEquals(List.of("validate", "reload"), calls);
    }

    private static SimpleCommand.Invocation invocation(Set<String> permissions, String... arguments) {
        var source = (CommandSource) Proxy.newProxyInstance(CommandSource.class.getClassLoader(),
                new Class<?>[]{CommandSource.class}, (proxy, method, args) -> {
                    if (method.getName().equals("hasPermission")) {
                        return permissions.contains(((String) args[0]).replace("consentgate.admin.", ""));
                    }
                    return null;
                });
        return (SimpleCommand.Invocation) Proxy.newProxyInstance(SimpleCommand.Invocation.class.getClassLoader(),
                new Class<?>[]{SimpleCommand.Invocation.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "source" -> source;
                    case "arguments" -> arguments;
                    case "alias" -> "consentgate";
                    default -> null;
                });
    }
}
