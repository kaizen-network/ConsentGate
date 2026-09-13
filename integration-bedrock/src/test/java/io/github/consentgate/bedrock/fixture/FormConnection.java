package io.github.consentgate.bedrock.fixture;

import org.geysermc.cumulus.form.Form;

public interface FormConnection {
    boolean sendForm(Form form);
    void closeForm();
}
