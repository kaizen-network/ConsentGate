package io.github.consentgate.velocity;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DenialCompletionTest {
    @Test void releasesHeldConnectionBeforeDisconnectingPlayer() {
        var calls = new ArrayList<String>();

        DenialCompletion.releaseThenDisconnect(() -> calls.add("release"), () -> calls.add("disconnect"),
                failure -> calls.add("failure"));

        assertEquals(java.util.List.of("release", "disconnect"), calls);
    }

    @Test void reportsDisconnectFailureAfterRelease() {
        var calls = new ArrayList<String>();

        DenialCompletion.releaseThenDisconnect(() -> calls.add("release"), () -> {
            calls.add("disconnect");
            throw new IllegalStateException("closed");
        }, failure -> calls.add(failure.getMessage()));

        assertEquals(java.util.List.of("release", "disconnect", "closed"), calls);
    }
}
