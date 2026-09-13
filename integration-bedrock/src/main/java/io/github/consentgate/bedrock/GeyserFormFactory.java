package io.github.consentgate.bedrock;

import io.github.consentgate.presentation.SafeTextFormatter;
import org.geysermc.cumulus.form.Form;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/** Loaded with the same Cumulus classes as the receiving Geyser connection. */
public final class GeyserFormFactory {
    private GeyserFormFactory() { }

    public static BedrockView create(Predicate<Object> sender, Runnable close, SafeTextFormatter formatter,
                                     String buttonColor, Function<String, String> messages, BooleanSupplier active,
                                     Runnable leave, Consumer<RuntimeException> failure) {
        return new NativeBedrockForms(new NativeBedrockForms.Transport() {
            @Override public boolean send(Form form) { return sender.test(form); }
            @Override public void close() { close.run(); }
        }, formatter, buttonColor, messages, active, leave, failure);
    }
}
