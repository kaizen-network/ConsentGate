package io.github.consentgate.spigot;

import io.github.consentgate.core.admission.AdmissionSession;
import io.github.consentgate.core.config.ConsentGateConfig;
import io.github.consentgate.presentation.InterfaceMessages;
import io.github.consentgate.presentation.SafeTextFormatter;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.dialog.Dialog;
import net.md_5.bungee.api.dialog.DialogBase;
import net.md_5.bungee.api.dialog.MultiActionDialog;
import net.md_5.bungee.api.dialog.action.ActionButton;
import net.md_5.bungee.api.dialog.action.CustomClickAction;
import net.md_5.bungee.api.dialog.body.PlainMessageBody;
import net.md_5.bungee.api.dialog.input.BooleanInput;
import net.md_5.bungee.api.dialog.input.DialogInput;
import net.md_5.bungee.chat.ComponentSerializer;
import java.util.ArrayList;
import java.util.List;

/** The same screens and action IDs as the Paper dialogs, built with Spigot's BungeeCord dialog API. */
final class SpigotDialogs {
    private final ConsentGateConfig config;
    private final SafeTextFormatter text;
    private final InterfaceMessages messages;
    SpigotDialogs(ConsentGateConfig config, InterfaceMessages messages) {
        this.config = config;
        this.text = new SafeTextFormatter(config.appearance());
        this.messages = messages;
    }
    String message(String locale, String key) { return messages.text(locale, config.defaultLocale(), key); }
    Dialog language(String locale, String token) {
        var selector = config.languageSelector();
        var buttons = new ArrayList<ActionButton>();
        int index = 0;
        for (String label : selector.options().values()) buttons.add(button(label, "language/" + index++ + "/" + token));
        return dialog(text.title(selector.title()), text.text(selector.prompt()), List.of(), buttons,
                button(message(locale, "leave"), "selector-leave/" + token), selector.columns(), true);
    }
    Dialog summary(AdmissionSession session, String locale, boolean error) {
        Component body = text.text(message(locale, "prompt"));
        var inputs = new ArrayList<DialogInput>();
        var buttons = new ArrayList<ActionButton>();
        var selected = session.selections();
        int index = 0;
        for (var document : session.request().documents()) {
            body = body.append(Component.text("\n\n")).append(text.accent(document.title()))
                    .append(text.mutedPlain(" (" + message(locale, "version") + " " + document.version() + ")"))
                    .append(Component.newline()).append(text.text(document.summary()));
            inputs.add(new BooleanInput("document_" + index, chat(text.text(document.checkbox())))
                    .initial(selected.getOrDefault(document.id(), false)));
            buttons.add(button(document.readButton(), "read/" + index++ + "/" + session.token()));
        }
        if (error) body = body.append(Component.text("\n\n")).append(text.error(message(locale, "required")));
        buttons.add(button(message(locale, "continue"), "accept/" + session.token()));
        return dialog(text.title(message(locale, "title")), body, inputs, buttons,
                button(message(locale, "leave"), "leave/" + session.token()), 2, true);
    }
    Dialog page(AdmissionSession session, String locale, int documentIndex, int pageIndex) {
        var document = session.request().documents().get(documentIndex);
        var page = document.pages().get(pageIndex);
        Component title = text.accent(document.title());
        if (document.pages().size() > 1 && !text.accent(page.title()).equals(title)) {
            title = title.append(text.muted(": ")).append(text.accent(page.title()));
        }
        Component body = text.text(page.body());
        if (document.pages().size() > 1) body = body.append(Component.text("\n\n")).append(text.mutedPlain(
                message(locale, "page").replace("{page}", String.valueOf(pageIndex + 1))
                        .replace("{pages}", String.valueOf(document.pages().size()))));
        var buttons = new ArrayList<ActionButton>();
        if (pageIndex > 0) buttons.add(button(message(locale, "previous"), "previous/" + documentIndex + "/" + pageIndex + "/" + session.token()));
        if (pageIndex + 1 < document.pages().size()) buttons.add(button(message(locale, "next"), "next/" + documentIndex + "/" + pageIndex + "/" + session.token()));
        var back = button(message(locale, "back"), "back/" + session.token());
        if (buttons.isEmpty()) buttons.add(back);
        return dialog(title, body, List.of(), buttons, document.pages().size() > 1 ? back : null, 2, document.pages().size() > 1);
    }
    /** Shared formatting goes through Adventure (shaded in this JAR), then JSON text, then BungeeCord components. */
    static BaseComponent chat(Component component) {
        return ComponentSerializer.deserialize(GsonComponentSerializer.gson().serialize(component));
    }
    private ActionButton button(String label, String action) {
        return new ActionButton(chat(text.button(label)), null, 200, new CustomClickAction("consentgate:" + action));
    }
    private Dialog dialog(Component title, Component body, List<DialogInput> inputs, List<ActionButton> buttons,
                          ActionButton exit, int columns, boolean canClose) {
        // NONE keeps the screen open after a button: the next screen replaces it, and clearDialog() on release closes it.
        // In play, CLOSE would show the world between screens, and the client's waiting screen (WAIT_FOR_RESPONSE) is not
        // closed by clearDialog(): ClientCommonPacketListenerImpl.clearDialog() only closes dialog screens. Escape and the
        // exit action still close the screen, since the client always runs those as CLOSE. The game rejects a pausing dialog
        // with NONE, and a server never pauses anyway.
        var base = new DialogBase(chat(title)).body(List.of(new PlainMessageBody(chat(body), 500))).inputs(inputs)
                .canCloseWithEscape(canClose).pause(false).afterAction(DialogBase.AfterAction.NONE);
        return new MultiActionDialog(base, buttons, columns, exit);
    }
}
