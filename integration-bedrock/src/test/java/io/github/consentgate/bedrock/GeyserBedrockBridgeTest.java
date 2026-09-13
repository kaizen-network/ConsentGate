package io.github.consentgate.bedrock;

import io.github.consentgate.core.admission.AdmissionDocument;
import io.github.consentgate.core.admission.AdmissionRequest;
import io.github.consentgate.core.admission.AdmissionSession;
import io.github.consentgate.core.config.DialogAppearance;
import io.github.consentgate.core.document.DocumentLoader;
import io.github.consentgate.core.storage.ShownDocument;
import io.github.consentgate.presentation.SafeTextFormatter;
import org.geysermc.cumulus.form.Form;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.net.URL;
import java.net.URLClassLoader;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class GeyserBedrockBridgeTest {
    private static final String API = "io.github.consentgate.bedrock.fixture.FormConnection";

    @BeforeAll static void usesRequestedArtifact() throws Exception {
        String artifact = System.getProperty("consentgate.bedrockArtifact");
        if (artifact != null) {
            assertEquals(Path.of(artifact).toRealPath(),
                    Path.of(GeyserBedrockBridge.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath(),
                    "Packaged checks must load the renderer bridge from the supplied JAR");
        }
    }

    @Test void sendsFormsAndHandlesResponsesWithTwoIndependentProviders() throws Exception {
        try (var first = new Provider(); var second = new Provider()) {
            assertNotSame(first.loadClass(Form.class.getName()), second.loadClass(Form.class.getName()));
            assertNotSame(Form.class, first.loadClass(Form.class.getName()));
            ClassLoader firstRenderer = exercise(first);
            assertSame(firstRenderer, exercise(first), "Reuse the renderer loader for one provider");
            assertNotSame(firstRenderer, exercise(second), "Different providers need independent renderers");
        }
    }

    @Test void providerDeliveryFailureDoesNotAccept() throws Exception {
        try (var provider = new Provider()) {
            var failures = new AtomicInteger();
            var session = session();
            Class<?> api = provider.loadClass(API);
            Object connection = Proxy.newProxyInstance(provider, new Class<?>[]{api}, (proxy, method, args) -> {
                throw new LinkageError("Simulated provider failure");
            });
            var view = GeyserBedrockBridge.forConnection(api, connection, new SafeTextFormatter(DialogAppearance.defaults()),
                    "dark_gray", key -> key, () -> true, () -> fail("Unexpected leave"), error -> {
                        assertInstanceOf(LinkageError.class, error.getCause());
                        failures.incrementAndGet();
                        session.end(AdmissionSession.Decision.FAILED);
                    });
            view.summary(session, false);
            assertEquals(1, failures.get());
            assertFalse(session.accepted());
        }
    }

    private static ClassLoader exercise(Provider provider) throws Exception {
        var sent = new ArrayList<Object>();
        var closes = new AtomicInteger();
        var leaves = new AtomicInteger();
        var session = session();
        Class<?> api = provider.loadClass(API);
        Class<?> form = provider.loadClass(Form.class.getName());
        Object connection = Proxy.newProxyInstance(provider, new Class<?>[]{api}, (proxy, method, args) -> {
            if (method.getName().equals("sendForm")) {
                assertTrue(form.isInstance(args[0]));
                assertFalse(args[0] instanceof Form, "Do not pass ConsentGate's independently resolved Cumulus copy");
                sent.add(args[0]);
                return true;
            }
            if (method.getName().equals("closeForm")) closes.incrementAndGet();
            return null;
        });
        var view = GeyserBedrockBridge.forConnection(api, connection, new SafeTextFormatter(DialogAppearance.defaults()),
                "dark_gray", key -> key, () -> true, leaves::incrementAndGet, error -> fail(error));
        view.summary(session, false);
        provider.reply(sent.getLast(), "0");
        provider.reply(sent.getLast(), "null");
        assertEquals(0, leaves.get(), "Closing a reading page returns to the summary");
        provider.reply(sent.getLast(), "2");
        provider.reply(sent.getLast(), "[true,false]");
        assertFalse(session.accepted());
        provider.reply(sent.getLast(), "[null,true,true]");
        assertTrue(session.accepted());
        view.close();
        view.close();
        assertEquals(1, closes.get());
        return view.getClass().getClassLoader();
    }

    private static AdmissionSession session() throws Exception {
        var documents = new ArrayList<AdmissionDocument>();
        for (String resource : List.of("/example-terms.yml", "/example-privacy.yml")) {
            var path = Path.of(GeyserBedrockBridgeTest.class.getResource(resource).toURI());
            var revision = new DocumentLoader().loadFile(path.getParent(), path);
            var translation = revision.translation("en-US", "en-US");
            documents.add(new AdmissionDocument(revision.id(), revision.version(), "en-US", translation.title(),
                    translation.summary(), translation.checkbox(), translation.readButton(), translation.pages(),
                    ShownDocument.from(revision, "en-US", "en-US")));
        }
        return new AdmissionSession(new AdmissionRequest(UUID.randomUUID(), UUID.randomUUID(), documents));
    }

    private static final class Provider extends URLClassLoader {
        Provider() {
            super(new URL[]{Form.class.getProtectionDomain().getCodeSource().getLocation(),
                    GeyserBedrockBridgeTest.class.getProtectionDomain().getCodeSource().getLocation()},
                    GeyserBedrockBridgeTest.class.getClassLoader());
        }

        @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) loaded = name.startsWith("org.geysermc.cumulus.") || name.equals(API)
                        ? findClass(name) : super.loadClass(name, false);
                if (resolve) resolveClass(loaded);
                return loaded;
            }
        }

        void reply(Object form, String response) throws Exception {
            var definitions = loadClass("org.geysermc.cumulus.form.impl.FormDefinitions");
            Object registry = definitions.getMethod("instance").invoke(null);
            Class<?> formType = loadClass(Form.class.getName());
            Object definition = definitions.getMethod("definitionFor", formType).invoke(registry, form);
            loadClass("org.geysermc.cumulus.form.impl.FormDefinition")
                    .getMethod("handleFormResponse", formType, String.class).invoke(definition, form, response);
        }
    }
}
