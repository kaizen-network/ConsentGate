package io.github.consentgate.velocity;

import org.geysermc.geyser.api.GeyserApi;
import org.geysermc.cumulus.form.Form;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;

final class GeyserBedrockBridge {
    private GeyserBedrockBridge() { }

    static BedrockView open(UUID player, SafeTextFormatter formatter, String buttonColor, Function<String, String> messages,
                            BooleanSupplier active, Runnable leave, Consumer<RuntimeException> failure) {
        var api = GeyserApi.api();
        if (api == null) throw new IllegalStateException("Geyser is not initialized");
        var connection = api.connectionByUuid(player);
        if (connection == null) return null;
        return new NativeBedrockForms(new NativeBedrockForms.Transport() {
            @Override public boolean send(Form form) { return connection.sendForm(form); }
            @Override public void close() { connection.closeForm(); }
        }, formatter, buttonColor, messages, active, leave, failure);
    }
}
