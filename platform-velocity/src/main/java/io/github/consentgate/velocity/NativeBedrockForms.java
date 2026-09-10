package io.github.consentgate.velocity;

import io.github.consentgate.core.admission.AdmissionSession;
import io.github.consentgate.core.config.LanguageSelectorConfig;
import org.geysermc.cumulus.form.CustomForm;
import org.geysermc.cumulus.form.Form;
import org.geysermc.cumulus.form.SimpleForm;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntConsumer;

final class NativeBedrockForms implements BedrockView {
    interface Transport {
        boolean send(Form form);
        void close();
    }

    private final Transport transport;
    private final SafeTextFormatter formatter;
    private final String buttonColor;
    private final Function<String, String> messages;
    private final BooleanSupplier active;
    private final Runnable leave;
    private final Consumer<RuntimeException> failure;
    private long sequence;
    private long current;
    private volatile boolean closed;
    private volatile AdmissionSession session;

    NativeBedrockForms(Transport transport, SafeTextFormatter formatter, String buttonColor, Function<String, String> messages,
                       BooleanSupplier active, Runnable leave, Consumer<RuntimeException> failure) {
        this.transport = transport;
        this.formatter = formatter;
        this.buttonColor = buttonColor;
        this.messages = messages;
        this.active = active;
        this.leave = leave;
        this.failure = failure;
    }

    private String text(String value) {
        return BedrockText.serialize(formatter.text(value));
    }

    private String label(String key) { return text(messages.apply(key)); }

    @Override public void language(LanguageSelectorConfig selector, IntConsumer selected) {
        var labels = new ArrayList<String>(selector.options().values());
        var actions = new ArrayList<Runnable>();
        for (int i = 0; i < labels.size(); i++) {
            int choice = i;
            actions.add(() -> selected.accept(choice));
        }
        labels.add(messages.apply("leave"));
        actions.add(leave);
        menu(selector.title(), selector.prompt(), labels, actions, leave);
    }

    @Override public void summary(AdmissionSession session, boolean error) {
        this.session = session;
        if (!session.pending()) return;
        StringBuilder body = new StringBuilder(messages.apply("prompt"));
        var labels = new ArrayList<String>();
        var actions = new ArrayList<Runnable>();
        for (int i = 0; i < session.request().documents().size(); i++) {
            var document = session.request().documents().get(i);
            body.append("\n\n").append(document.title()).append(" (").append(messages.apply("version"))
                    .append(' ').append(document.version()).append(")\n").append(document.summary());
            labels.add(document.readButton());
            int index = i;
            actions.add(() -> page(index, 0));
        }
        if (error) body.append("\n\n").append(messages.apply("required"));
        labels.add(messages.apply("continue"));
        actions.add(() -> acceptance(false));
        labels.add(messages.apply("leave"));
        actions.add(leave);
        menu(messages.apply("title"), body.toString(), labels, actions, leave);
    }

    private void page(int documentIndex, int pageIndex) {
        if (!session.pending()) return;
        var document = session.request().documents().get(documentIndex);
        var page = document.pages().get(pageIndex);
        String title = document.title();
        String body = page.body();
        if (document.pages().size() > 1) {
            title += ": " + page.title();
            body += "\n\n" + messages.apply("page").replace("{page}", String.valueOf(pageIndex + 1))
                    .replace("{pages}", String.valueOf(document.pages().size()));
        }
        var labels = new ArrayList<String>();
        var actions = new ArrayList<Runnable>();
        if (pageIndex > 0) {
            labels.add(messages.apply("previous"));
            actions.add(() -> page(documentIndex, pageIndex - 1));
        }
        if (pageIndex + 1 < document.pages().size()) {
            labels.add(messages.apply("next"));
            actions.add(() -> page(documentIndex, pageIndex + 1));
        }
        labels.add(messages.apply("back"));
        Runnable back = () -> summary(session, false);
        actions.add(back);
        menu(title, body, labels, actions, back);
    }

    private void acceptance(boolean error) {
        if (!available() || !session.pending()) return;
        long token = next();
        var builder = CustomForm.builder().title(label("title"));
        if (error) builder.label(label("required"));
        var documents = session.request().documents();
        var selections = session.selections();
        for (var document : documents) {
            builder.toggle(text(document.checkbox()), selections.getOrDefault(document.id(), false));
        }
        int offset = error ? 1 : 0;
        builder.closedResultHandler(() -> respond(token, () -> summary(session, false)));
        builder.invalidResultHandler(() -> respond(token, leave));
        builder.validResultHandler(response -> respond(token, () -> {
            var supplied = new LinkedHashMap<String, Boolean>();
            for (int i = 0; i < documents.size(); i++) {
                supplied.put(documents.get(i).id(), response.asToggle(i + offset));
            }
            if (!session.accept(session.token(), supplied) && session.pending()) acceptance(true);
        }));
        send(token, builder.build());
    }

    private void menu(String title, String body, List<String> labels, List<Runnable> actions, Runnable onClose) {
        if (!available()) return;
        long token = next();
        var builder = SimpleForm.builder().title(text(title)).content(text(body));
        labels.forEach(value -> builder.button(BedrockText.serialize(formatter.format(value, buttonColor))));
        builder.closedResultHandler(() -> respond(token, onClose));
        builder.invalidResultHandler(() -> respond(token, leave));
        builder.validResultHandler(response -> respond(token, () -> {
            int selected = response.clickedButtonId();
            if (selected < 0 || selected >= actions.size()) throw new IllegalArgumentException("Invalid form button");
            actions.get(selected).run();
        }));
        send(token, builder.build());
    }

    private boolean available() { return !closed && active.getAsBoolean(); }
    private synchronized long next() { current = ++sequence; return current; }

    private void respond(long token, Runnable action) {
        synchronized (this) {
            if (!available() || current != token) return;
            current = 0;
        }
        try { action.run(); }
        catch (RuntimeException ex) { failure.accept(ex); }
    }

    private void send(long token, Form form) {
        RuntimeException error = null;
        synchronized (this) {
            if (!available() || current != token) return;
            try {
                if (!transport.send(form)) throw new IllegalStateException("Geyser could not send the consent form");
            } catch (RuntimeException ex) {
                current = 0;
                error = ex;
            }
        }
        if (error != null) failure.accept(error);
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        current = 0;
        transport.close();
    }
}
