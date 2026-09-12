package io.github.consentgate.velocity;

import io.github.consentgate.presentation.SafeTextFormatter;

import io.github.consentgate.core.GateSession;
import io.github.consentgate.core.admission.AdmissionDocument;
import io.github.consentgate.core.admission.AdmissionRequest;
import io.github.consentgate.core.admission.AdmissionSession;
import io.github.consentgate.core.config.DialogAppearance;
import io.github.consentgate.core.config.LanguageSelectorConfig;
import io.github.consentgate.core.document.DocumentLoader;
import io.github.consentgate.core.storage.ShownDocument;
import org.geysermc.cumulus.component.ToggleComponent;
import org.geysermc.cumulus.form.CustomForm;
import org.geysermc.cumulus.form.Form;
import org.geysermc.cumulus.form.SimpleForm;
import org.geysermc.cumulus.form.impl.FormDefinitions;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class NativeBedrockFormsTest {
    private final List<Form> sent = new ArrayList<>();
    private final AtomicBoolean active = new AtomicBoolean(true);
    private final AtomicInteger leaves = new AtomicInteger();
    private final AtomicInteger failures = new AtomicInteger();
    private final AtomicInteger closes = new AtomicInteger();
    private final AdmissionSession session = session();
    private boolean canSend = true;
    private final NativeBedrockForms forms = new NativeBedrockForms(new NativeBedrockForms.Transport() {
        @Override public boolean send(Form form) { sent.add(form); return canSend; }
        @Override public void close() { closes.incrementAndGet(); }
    }, new SafeTextFormatter(DialogAppearance.defaults()), "dark_gray", this::message, active::get, () -> {
        leaves.incrementAndGet();
        session.decline(session.token());
        active.set(false);
    }, error -> {
        failures.incrementAndGet();
        session.end(GateSession.Decision.FAILED);
        active.set(false);
    });

    @Test void rendersButtonMenuReadingPagesAndSeparateUncheckedToggles() throws Exception {
        forms.summary(session, false);
        SimpleForm summary = assertInstanceOf(SimpleForm.class, last());
        assertEquals(4, summary.buttons().size());
        summary.buttons().forEach(button -> assertTrue(button.text().startsWith("\u00a7r\u00a78")));
        assertFalse(summary.content().contains("<gold>"));
        reply(summary, "0");
        SimpleForm first = assertInstanceOf(SimpleForm.class, last());
        assertEquals(2, first.buttons().size());
        reply(first, "0");
        SimpleForm second = assertInstanceOf(SimpleForm.class, last());
        assertEquals(2, second.buttons().size());
        reply(second, "null");
        assertInstanceOf(SimpleForm.class, last());
        assertEquals(0, leaves.get());
        reply(last(), "2");
        CustomForm accept = assertInstanceOf(CustomForm.class, last());
        assertEquals(2, accept.content().size());
        for (var component : accept.content()) assertFalse(assertInstanceOf(ToggleComponent.class, component).defaultValue());
        reply(accept, "[true,true]");
        assertTrue(session.accepted());
        assertEquals(0, failures.get());
    }

    @Test void uncheckedSubmitRetainsSelectionsAndRequiresEveryAgreement() throws Exception {
        forms.summary(session, false);
        reply(last(), "2");
        Form previous = last();
        reply(previous, "[true,false]");
        assertFalse(session.accepted());
        CustomForm retry = assertInstanceOf(CustomForm.class, last());
        assertEquals(3, retry.content().size());
        assertTrue(assertInstanceOf(ToggleComponent.class, retry.content().get(1)).defaultValue());
        assertFalse(assertInstanceOf(ToggleComponent.class, retry.content().get(2)).defaultValue());
        reply(previous, "[true,true]");
        assertFalse(session.accepted());
        reply(retry, "[null,true,true]");
        assertTrue(session.accepted());
    }

    @Test void bundledSummariesAndPolicyBodiesDoNotInheritBold() throws Exception {
        forms.summary(session, false);
        String summary = assertInstanceOf(SimpleForm.class, last()).content();
        assertBoldAt(summary, "Ketentuan Layanan", true);
        assertBoldAt(summary, "Aturan dan ketentuan", false);
        assertBoldAt(summary, "Cara server Minecraft", false);
        reply(last(), "0");
        String terms = assertInstanceOf(SimpleForm.class, last()).content();
        assertBoldAt(terms, "Templat awal.", true);
        assertBoldAt(terms, "Ganti semua", false);
        reply(last(), "1");
        reply(last(), "1");
        String privacy = assertInstanceOf(SimpleForm.class, last()).content();
        assertBoldAt(privacy, "Jelaskan praktik", false);
    }

    private static void assertBoldAt(String formatted, String needle, boolean expected) {
        int end = formatted.indexOf(needle);
        assertTrue(end >= 0, needle);
        boolean bold = false;
        for (int i = 0; i < end - 1; i++) {
            if (formatted.charAt(i) != '\u00a7') continue;
            char code = formatted.charAt(++i);
            if (code == 'r') bold = false;
            if (code == 'l') bold = true;
        }
        assertEquals(expected, bold, needle);
    }

    @Test void staleMenusCannotAcceptOrReopenClosedForms() throws Exception {
        forms.summary(session, false);
        Form old = last();
        reply(old, "0");
        int count = sent.size();
        reply(old, "2");
        assertEquals(count, sent.size());
        forms.close();
        forms.close();
        reply(last(), "0");
        assertEquals(count, sent.size());
        assertEquals(1, closes.get());
        assertFalse(session.accepted());
    }

    @Test void closingAcceptanceReturnsToDocumentsWithoutAccepting() throws Exception {
        forms.summary(session, false);
        reply(last(), "2");
        Form old = last();
        reply(last(), "null");
        assertInstanceOf(SimpleForm.class, last());
        assertEquals(0, leaves.get());
        assertFalse(session.accepted());
        assertTrue(session.pending());
        int count = sent.size();
        reply(old, "[true,true]");
        assertEquals(count, sent.size());
        assertFalse(session.accepted());
        reply(last(), "0");
        assertInstanceOf(SimpleForm.class, last());
        reply(last(), "null");
        reply(last(), "2");
        reply(last(), "[true,true]");
        assertTrue(session.accepted());
    }

    @Test void closingRetryReturnsToMenuAndClosingMenuStillDeclines() throws Exception {
        forms.summary(session, false);
        reply(last(), "2");
        reply(last(), "[true,false]");
        reply(last(), "null");
        assertInstanceOf(SimpleForm.class, last());
        assertTrue(session.pending());
        assertEquals(0, leaves.get());
        reply(last(), "null");
        assertEquals(1, leaves.get());
        assertFalse(session.accepted());
        assertFalse(session.pending());
    }

    @Test void repeatedConcurrentChoicesAdvanceOnlyOnce() throws Exception {
        forms.summary(session, false);
        Form menu = last();
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(4)) {
            var jobs = new ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 20; i++) jobs.add(executor.submit(() -> {
                try { reply(menu, "2"); }
                catch (Exception ex) { throw new RuntimeException(ex); }
            }));
            for (var job : jobs) job.get();
        }
        assertEquals(2, sent.size());
        assertInstanceOf(CustomForm.class, last());
        assertFalse(session.accepted());
    }

    @Test void malformedResponsesNeverGrantAcceptance() throws Exception {
        for (String input : List.of("[true]", "[true,true,true]", "[\"true\",true]", "[1,true]")) {
            var fixture = new NativeBedrockFormsTest();
            fixture.forms.summary(fixture.session, false);
            reply(fixture.last(), "2");
            reply(fixture.last(), input);
            assertFalse(fixture.session.accepted(), input);
            assertEquals(1, fixture.leaves.get(), input);
        }
    }

    @Test void disconnectAndSendFailureCannotAdvanceFlow() throws Exception {
        forms.summary(session, false);
        active.set(false);
        reply(last(), "2");
        assertEquals(1, sent.size());
        active.set(true);
        canSend = false;
        forms.summary(session, false);
        assertEquals(1, failures.get());
        assertFalse(session.pending());
    }

    @Test void languageChoiceIsClaimedOnceAndCloseDeclines() throws Exception {
        var options = new LinkedHashMap<String, String>();
        options.put("en-US", "English");
        options.put("id-ID", "Bahasa Indonesia");
        var selector = new LanguageSelectorConfig(true, "Language", "Choose", 2, options);
        var choice = new AtomicInteger(-1);
        forms.language(selector, choice::set);
        Form old = last();
        reply(old, "1");
        reply(old, "0");
        assertEquals(1, choice.get());
        forms.language(selector, choice::set);
        reply(last(), "null");
        assertEquals(1, leaves.get());
    }

    private Form last() { return sent.getLast(); }
    private static void reply(Form form, String response) throws Exception {
        FormDefinitions.instance().definitionFor(form).handleFormResponse(form, response);
    }
    private String message(String key) { return key; }

    private static AdmissionSession session() {
        try {
            var documents = new ArrayList<AdmissionDocument>();
            for (String resource : List.of("/example-terms.yml", "/example-privacy.yml")) {
                var path = Path.of(NativeBedrockFormsTest.class.getResource(resource).toURI());
                var revision = new DocumentLoader().loadFile(path.getParent(), path);
                var translation = revision.translation("id-ID", "en-US");
                documents.add(new AdmissionDocument(revision.id(), revision.version(), "id-ID", translation.title(),
                        translation.summary(), translation.checkbox(), translation.readButton(), translation.pages(),
                        ShownDocument.from(revision, "id-ID", "en-US")));
            }
            return new AdmissionSession(new AdmissionRequest(UUID.randomUUID(), UUID.randomUUID(), documents));
        } catch (Exception ex) { throw new IllegalStateException(ex); }
    }
}
