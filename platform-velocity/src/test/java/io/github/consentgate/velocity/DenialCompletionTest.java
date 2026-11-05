package io.github.consentgate.velocity;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DenialCompletionTest {
    @Test void releasesHeldConnectionBeforeDisconnectingPlayer() {
        var calls = new ArrayList<String>();

        DenialCompletion.releaseThenDisconnect(() -> calls.add("release"), () -> true, () -> calls.add("disconnect"),
                failure -> calls.add("failure"));

        assertEquals(java.util.List.of("release", "disconnect"), calls);
    }

    @Test void releasesAlreadyDisconnectedConnectionWithoutDisconnectingAgain() {
        var calls = new ArrayList<String>();

        DenialCompletion.releaseThenDisconnect(() -> calls.add("release"), () -> false,
                () -> calls.add("disconnect"), failure -> calls.add("failure"));

        assertEquals(java.util.List.of("release"), calls);
    }

    @Test void checksActivityAfterReleasingHeldConnection() {
        var calls = new ArrayList<String>();
        var active = new AtomicBoolean(true);

        DenialCompletion.releaseThenDisconnect(() -> {
            calls.add("release");
            active.set(false);
        }, active::get, () -> calls.add("disconnect"), failure -> calls.add("failure"));

        assertEquals(java.util.List.of("release"), calls);
    }

    @Test void reportsDisconnectFailureAfterRelease() {
        var calls = new ArrayList<String>();

        DenialCompletion.releaseThenDisconnect(() -> calls.add("release"), () -> true, () -> {
            calls.add("disconnect");
            throw new IllegalStateException("closed");
        }, failure -> calls.add(failure.getMessage()));

        assertEquals(java.util.List.of("release", "disconnect", "closed"), calls);
    }
}
