package io.github.consentgate.core.admin;

import io.github.consentgate.core.document.DocumentCatalog;
import io.github.consentgate.core.document.LocaleTag;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongSupplier;

/** One connection per request; all queued previews expire after five minutes. */
public final class PreviewQueue {
    public static final String COMPLETE = "Preview complete. No acceptance was saved. Reconnect for normal admission.";
    private static final long TTL = Duration.ofMinutes(5).toNanos();
    private final Map<UUID, Entry> queued = new HashMap<>();
    private final LongSupplier clock;

    public record Choice(String locale) { }
    private record Entry(Choice choice, long created) { }

    public PreviewQueue() { this(System::nanoTime); }
    PreviewQueue(LongSupplier clock) { this.clock = clock; }

    public synchronized String schedule(UUID player, String locale, DocumentCatalog catalog) {
        expire();
        if ("cancel".equalsIgnoreCase(locale)) {
            return queued.remove(player) == null ? "No preview was queued for " + player + "." : "Preview cancelled for " + player + ".";
        }
        if (locale != null) {
            locale = LocaleTag.normalize(locale);
            for (var document : catalog.required()) {
                if (!document.translations().containsKey(locale)) {
                    throw new IllegalArgumentException("No exact " + locale + " translation for " + document.id());
                }
            }
        }
        if (queued.size() >= 128 && !queued.containsKey(player)) {
            throw new IllegalArgumentException("Preview queue is full. Cancel an existing preview or wait five minutes.");
        }
        queued.put(player, new Entry(new Choice(locale), clock.getAsLong()));
        return "Preview queued for " + player + ". Connect within five minutes. It will disconnect without saving acceptance.";
    }

    public synchronized Optional<Choice> take(UUID player) {
        expire();
        var entry = queued.remove(player);
        return entry == null ? Optional.empty() : Optional.of(entry.choice());
    }

    public synchronized void clear() { queued.clear(); }

    private void expire() {
        long now = clock.getAsLong();
        queued.values().removeIf(entry -> now - entry.created() >= TTL || now - entry.created() < 0);
    }
}
