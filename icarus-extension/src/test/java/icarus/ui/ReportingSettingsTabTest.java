package icarus.ui;

import burp.api.montoya.ui.UserInterface;
import icarus.core.I18n;
import icarus.core.ModuleConfig;
import icarus.report.DefaultReportProfileManager;
import icarus.report.model.ContentPolicy;
import icarus.report.model.HtmlTheme;
import icarus.report.model.ReportProfile;

import javax.swing.SwingUtilities;
import javax.swing.text.JTextComponent;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Drives the real Reporting tab without a Burp instance (a null-returning UserInterface proxy)
 * and checks Save Profile keeps what the form does not edit, and that the section editor
 * empties when a profile has no sections.
 * {@code java -cp build_manual/classes:build_manual/test-classes:libs/* icarus.ui.ReportingSettingsTabTest}
 */
public final class ReportingSettingsTabTest {

    private static final String RICH = "src/test/resources/reports/canvas/rich.json";

    public static void main(String[] args) throws Exception {
        I18n.initialize(new ModuleConfig());
        saveKeepsFieldsTheFormDoesNotEdit();
        editorClearsWhenProfileHasNoSections();
        editorClearsWhenLastSectionIsRemoved();
        System.out.println("ReportingSettingsTabTest: all checks passed.");
    }

    /** Import rich.json, save it through the tab untouched: only form-edited fields may differ. */
    private static void saveKeepsFieldsTheFormDoesNotEdit() throws Exception {
        ModuleConfig cfg = new ModuleConfig();
        DefaultReportProfileManager mgr = new DefaultReportProfileManager(cfg);
        ReportProfile imported = mgr.importJson(Files.readString(Path.of(RICH)));
        mgr.setActive(imported.id());
        ReportingSettingsTab tab = newTab(cfg, mgr);
        onEdt(tab::save);

        ReportProfile saved = mgr.get(imported.id());
        assert saved != null : "saved profile missing";
        assert saved.id().equals(imported.id()) && saved.name().equals(imported.name()) && saved.locale().equals(imported.locale())
            && java.util.Objects.equals(saved.basedOnId(), imported.basedOnId()) && !saved.builtIn() : "identity fields changed";
        assert saved.coverRenderer() == imported.coverRenderer() && saved.findingRenderer() == imported.findingRenderer() : "renderers changed";
        assert saved.sections().equals(imported.sections()) : "sections changed (CANVAS_PAGE spec included)";
        assert saved.branding().equals(imported.branding()) : "branding changed: " + saved.branding();
        assert saved.pdfTheme().equals(imported.pdfTheme()) : "pdfTheme changed (page box, text/heading colors)";

        // Form-edited but shape-only differences: the severity palette is one set of swatches shared
        // by PDF and HTML, so HTML gains the keys the form writes; everything else must match.
        HtmlTheme h = saved.htmlTheme(), hi = imported.htmlTheme();
        assert h.severityHex().entrySet().containsAll(hi.severityHex().entrySet()) : "html severity colors changed";
        assert h.equals(new HtmlTheme(hi.primaryHex(), hi.secondaryHex(), hi.backgroundHex(), hi.cardBackgroundHex(),
            hi.textHex(), hi.borderHex(), h.severityHex(), hi.fontStack(), hi.dark())) : "htmlTheme changed: " + h;

        // Finding fields are checkboxes, so their order is not preserved; the set is.
        ContentPolicy c = saved.content(), ci = imported.content();
        assert new java.util.HashSet<>(c.findingFields()).equals(new java.util.HashSet<>(ci.findingFields())) : "finding fields changed";
        assert c.equals(new ContentPolicy(ci.includeEvidence(), ci.includeHttpRequest(), ci.includeHttpResponse(), ci.maxRequestBytes(),
            ci.maxResponseBytes(), c.findingFields(), ci.cweMode(), ci.cweAllowlist(), ci.includeTocBookmarks())) : "content changed: " + c;
        System.out.println("   [PASSED] Save keeps non-form fields of the imported profile.");
    }

    /** Previous profile's section text must not stay in the editor when the new one has none. */
    private static void editorClearsWhenProfileHasNoSections() throws Exception {
        ModuleConfig cfg = new ModuleConfig();
        DefaultReportProfileManager mgr = new DefaultReportProfileManager(cfg);
        ReportProfile withSections = mgr.importJson(Files.readString(Path.of(RICH)));
        ReportProfile empty = mgr.importJson("{\"schemaVersion\":\"1\",\"name\":\"Empty\"}");
        mgr.setActive(withSections.id());
        ReportingSettingsTab tab = newTab(cfg, mgr);
        JTextComponent title = editor(tab, "titleField");
        JTextComponent body = editor(tab, "bodyWell");
        onEdt(() -> {});
        assert "Executive Cover Page".equals(title.getText()) : "editor not populated: " + title.getText();

        // Same path as Import: refreshProfileList() with the listener suppressed.
        mgr.setActive(empty.id());
        onEdt(() -> invoke(tab, "refreshProfileList"));
        assertCleared(title, body, "after import/refresh");

        // Same path as the profile dropdown: loadProfileIntoForm() directly.
        mgr.setActive(withSections.id());
        onEdt(() -> invoke(tab, "refreshProfileList"));
        onEdt(() -> {}); // first section is selected via invokeLater
        assert "Executive Cover Page".equals(title.getText()) : "editor not repopulated";
        onEdt(() -> invoke(tab, "loadProfileIntoForm", empty));
        assertCleared(title, body, "after switching profile");
        System.out.println("   [PASSED] Section editor clears for a profile with no sections.");
    }

    /** Deleting the only section must empty and disable the editor. */
    private static void editorClearsWhenLastSectionIsRemoved() throws Exception {
        ModuleConfig cfg = new ModuleConfig();
        DefaultReportProfileManager mgr = new DefaultReportProfileManager(cfg);
        ReportProfile one = mgr.importJson("{\"name\":\"One\",\"sections\":{\"nodes\":[{\"id\":\"CUSTOM_A\",\"enabled\":true,"
            + "\"order\":1,\"required\":false,\"rendererKey\":\"CUSTOM_A\",\"params\":{\"title\":\"Only\",\"content\":\"Body\"}}]}}");
        mgr.setActive(one.id());
        ReportingSettingsTab tab = newTab(cfg, mgr);
        JTextComponent title = editor(tab, "titleField");
        JTextComponent body = editor(tab, "bodyWell");
        onEdt(() -> {});
        assert "Only".equals(title.getText()) : "editor not populated: " + title.getText();

        onEdt(() -> invoke(tab, "removeSection"));
        assertCleared(title, body, "after removing the last section");
        System.out.println("   [PASSED] Section editor clears when the last section is removed.");
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private static ReportingSettingsTab newTab(ModuleConfig cfg, DefaultReportProfileManager mgr) throws Exception {
        UserInterface ui = (UserInterface) Proxy.newProxyInstance(UserInterface.class.getClassLoader(),
            new Class<?>[]{UserInterface.class}, (proxy, m, a) -> switch (m.getName()) {
                case "toString" -> "ui";
                case "hashCode" -> 0;
                case "equals" -> proxy == a[0];
                default -> null;
            });
        ReportingSettingsTab[] tab = new ReportingSettingsTab[1];
        onEdt(() -> tab[0] = new ReportingSettingsTab(null, cfg, new ThemeHelper(ui), mgr));
        onEdt(() -> {}); // let the list's invokeLater selection run
        return tab[0];
    }

    private static void assertCleared(JTextComponent title, JTextComponent body, String when) {
        assert title.getText().isEmpty() : "title still shows previous profile " + when + ": " + title.getText();
        assert body.getText().isEmpty() : "body still shows previous profile " + when + ": " + body.getText();
        assert !title.isEnabled() && !body.isEnabled() : "editor not disabled " + when;
    }

    private static JTextComponent editor(ReportingSettingsTab tab, String field) throws Exception {
        Object detail = get(tab, "detailPane");
        return (JTextComponent) get(detail, field);
    }

    private static Object get(Object target, String name) throws Exception {
        var f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(target);
    }

    private static void invoke(ReportingSettingsTab tab, String name, Object... args) {
        try {
            for (Method m : ReportingSettingsTab.class.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == args.length) {
                    m.setAccessible(true);
                    m.invoke(tab, args);
                    return;
                }
            }
            throw new IllegalStateException("no method " + name);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Runs on the EDT and waits, so queued invokeLater work from earlier steps has run too. */
    private static void onEdt(Runnable r) throws Exception {
        SwingUtilities.invokeAndWait(r);
    }
}
