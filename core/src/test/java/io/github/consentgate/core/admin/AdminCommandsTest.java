package io.github.consentgate.core.admin;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

class AdminCommandsTest {
    private final UUID player = UUID.randomUUID();
    private final List<String> calls = new ArrayList<>();
    private final List<String> replies = new ArrayList<>();
    private final AdminCommands command = new AdminCommands(name -> name.equals("TestPlayer") ? Optional.of(player) : Optional.empty(),
            new AdminCommands.Operations() {
                public void status(UUID id, Consumer<String> reply) { calls.add("status:" + id); }
                public void reset(UUID id, Consumer<String> reply) { calls.add("reset:" + id); }
                public void configuration(boolean apply, Consumer<String> reply) { calls.add(apply ? "reload" : "validate"); }
                public void preview(UUID id, String locale, Consumer<String> reply) { calls.add("preview:" + id + ":" + locale); }
                public void document(String[] args, Consumer<String> reply) { calls.add("document:" + String.join(":", args)); }
            });

    private void execute(Set<String> permissions, String... args) {
        command.execute(args, permission -> permissions.contains(permission.replace("consentgate.admin.", "")), replies::add);
    }

    @Test void resolvesOnlineNamesAndCanonicalOfflineUuids() {
        execute(Set.of("status"), "status", "TestPlayer");
        execute(Set.of("reset"), "RESET", player.toString().toUpperCase(Locale.ROOT));
        assertEquals(List.of("status:" + player, "reset:" + player), calls);
    }

    @Test void unknownNamesAndAbbreviatedUuidsAreNotGuessed() {
        for (String target : List.of("UnknownPlayer", "1-1-1-1-1", "not-a-uuid")) execute(Set.of("reset"), "reset", target);
        assertTrue(calls.isEmpty());
        assertEquals(3, replies.size());
    }

    @Test void everyActionNeedsItsOwnPermission() {
        execute(Set.of("status"), "reset", player.toString());
        execute(Set.of("validate"), "reload");
        execute(Set.of(), "status", player.toString());
        assertTrue(calls.isEmpty());
        assertTrue(replies.stream().allMatch(text -> text.contains("permission")));
    }

    @Test void validationAndReloadAreSeparateActions() {
        execute(Set.of("validate"), "validate");
        execute(Set.of("reload"), "reload");
        assertEquals(List.of("validate", "reload"), calls);
    }

    @Test void malformedUsageDoesNotRunAnOperation() {
        for (String[] args : List.of(new String[0], new String[]{"reset"}, new String[]{"reload", "extra"},
                new String[]{"status", player.toString(), "extra"}, new String[]{"unknown"})) execute(Set.of("status", "reset", "reload"), args);
        assertTrue(calls.isEmpty());
        assertEquals(5, replies.size());
    }

    @Test void previewAndDocumentHaveSeparatePermissionsAndArguments() {
        execute(Set.of("status", "reset"), "preview", player.toString());
        execute(Set.of("preview"), "document");
        assertTrue(calls.isEmpty());
        execute(Set.of("preview"), "preview", player.toString(), "id-ID");
        execute(Set.of("preview"), "preview", player.toString(), "cancel");
        execute(Set.of("document"), "document");
        execute(Set.of("document"), "document", "rules", "id-ID", "2");
        assertEquals(List.of("preview:" + player + ":id-ID", "preview:" + player + ":cancel", "document:", "document:rules:id-ID:2"), calls);
    }

    @Test void completionsDoNotExposeNamesOrUnauthorizedActions() {
        assertEquals(List.of("reset"), AdminCommands.suggest(new String[]{"r"}, permission -> permission.endsWith(".reset")));
        assertTrue(AdminCommands.suggest(new String[]{"status", ""}, permission -> true).isEmpty());
        assertFalse(AdminCommands.hasPermission(permission -> false));
        assertTrue(AdminCommands.hasPermission(permission -> permission.endsWith(".validate")));
    }
}
