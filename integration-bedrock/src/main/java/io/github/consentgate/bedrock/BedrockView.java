package io.github.consentgate.bedrock;

import io.github.consentgate.core.admission.AdmissionSession;
import io.github.consentgate.core.config.LanguageSelectorConfig;
import java.util.function.IntConsumer;

public interface BedrockView {
    void language(LanguageSelectorConfig selector, IntConsumer selected);
    void summary(AdmissionSession session, boolean error);
    void close();
}
