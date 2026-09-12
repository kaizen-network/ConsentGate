package io.github.consentgate.paper;

import io.github.consentgate.core.admission.AdmissionSession;
import io.github.consentgate.core.config.ConsentGateConfig;
import io.github.consentgate.presentation.InterfaceMessages;
import io.github.consentgate.presentation.SafeTextFormatter;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import java.util.ArrayList;
import java.util.List;

final class PaperDialogs {
    private final ConsentGateConfig config;
    private final SafeTextFormatter text;
    private final InterfaceMessages messages;
    PaperDialogs(ConsentGateConfig config, InterfaceMessages messages) {
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
            inputs.add(DialogInput.bool("document_" + index, text.text(document.checkbox()))
                    .initial(selected.getOrDefault(document.id(), false)).build());
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
    private ActionButton button(String label, String action) {
        return ActionButton.builder(text.button(label)).width(200)
                .action(DialogAction.customClick(Key.key("consentgate", action), null)).build();
    }
    private Dialog dialog(Component title, Component body, List<DialogInput> inputs, List<ActionButton> buttons,
                          ActionButton exit, int columns, boolean canClose) {
        return Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(title).canCloseWithEscape(canClose).afterAction(DialogBase.DialogAfterAction.CLOSE)
                        .body(List.of(DialogBody.plainMessage(body, 500))).inputs(inputs).build())
                .type(DialogType.multiAction(buttons).exitAction(exit).columns(columns).build()));
    }
}
