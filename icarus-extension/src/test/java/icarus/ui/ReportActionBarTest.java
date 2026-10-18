package icarus.ui;

import icarus.Orchestrator;
import icarus.autoauth.AutoAuthModule;
import icarus.core.I18n;
import icarus.core.ModuleConfig;
import icarus.evidence.EvidenceCapture;
import icarus.evidence.ReportGenerator;
import icarus.mcp.IcarusMcpServer;
import icarus.ui.evidence.EvidenceManagerTab;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.ui.Theme;

import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.Frame;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Preview, Generate HTML, and Export PDF are reachable from one bar,
 * and the evidence tab no longer carries a second copy.
 * {@code java -cp build_manual/classes:libs/montoya-api-2026.7.jar icarus.ui.ReportActionBarTest}
 */
public final class ReportActionBarTest {

    public static void main(String[] args) throws Exception {
        I18n.initialize(new ModuleConfig());
        clicksReachTheThreeActions();
        shellHostsOneBar();
        evidenceTabDoesNotRepeatThem();
        System.out.println("ReportActionBarTest: all checks passed.");
    }

    private static void clicksReachTheThreeActions() {
        List<String> hits = new ArrayList<>();
        ReportActionBar bar = new ReportActionBar(
                button -> hits.add("preview:" + button.getText()),
                button -> hits.add("html:" + button.getText()),
                button -> hits.add("pdf:" + button.getText()));
        check(ReportActionBar.NAME.equals(bar.getName()), "bar name");
        check(I18n.t("ui.tab.results.btn.preview").equals(bar.previewButton().getText()), "preview label");
        check(I18n.t("ui.tab.results.btn.generate_html").equals(bar.generateHtmlButton().getText()), "html label");
        check(I18n.t("ui.tab.results.btn.export_pdf").equals(bar.exportPdfButton().getText()), "pdf label");
        bar.previewButton().doClick();
        bar.generateHtmlButton().doClick();
        bar.exportPdfButton().doClick();
        check(hits.equals(List.of(
                "preview:" + I18n.t("ui.tab.results.btn.preview"),
                "html:" + I18n.t("ui.tab.results.btn.generate_html"),
                "pdf:" + I18n.t("ui.tab.results.btn.export_pdf"))),
                "clicks did not reach the three actions: " + hits);
    }

    private static void shellHostsOneBar() throws Exception {
        MontoyaApi api = (MontoyaApi) stub(MontoyaApi.class);
        ModuleConfig config = new ModuleConfig();
        I18n.initialize(config);
        EvidenceCapture evidence = new EvidenceCapture(api, config);
        Orchestrator orchestrator = new Orchestrator(api, List.of(), config, evidence,
                new ReportGenerator(api), new AutoAuthModule(api, config));
        SwingUtilities.invokeAndWait(() -> {
            IcarusTab tab = new IcarusTab(api, config, List.of(), orchestrator, new IcarusMcpServer(api, orchestrator));
            List<JButton> preview = buttonsNamed(tab.getComponent(), I18n.t("ui.tab.results.btn.preview"));
            List<JButton> html = buttonsNamed(tab.getComponent(), I18n.t("ui.tab.results.btn.generate_html"));
            List<JButton> pdf = buttonsNamed(tab.getComponent(), I18n.t("ui.tab.results.btn.export_pdf"));
            check(preview.size() == 1, "shell preview count " + preview.size());
            check(html.size() == 1, "shell html count " + html.size());
            check(pdf.size() == 1, "shell pdf count " + pdf.size());
            check(preview.get(0).getParent() instanceof ReportActionBar, "preview is not on the action bar");
            check(preview.get(0).getParent() == html.get(0).getParent(), "html left the action bar");
            check(preview.get(0).getParent() == pdf.get(0).getParent(), "pdf left the action bar");
            check(preview.get(0).getParent().getParent() == tab.getComponent(), "action bar is not on the shell");
        });
    }

    private static void evidenceTabDoesNotRepeatThem() throws Exception {
        MontoyaApi api = (MontoyaApi) stub(MontoyaApi.class);
        ModuleConfig config = new ModuleConfig();
        I18n.initialize(config);
        EvidenceCapture evidence = new EvidenceCapture(api, config);
        Orchestrator orchestrator = new Orchestrator(api, List.of(), config, evidence,
                new ReportGenerator(api), new AutoAuthModule(api, config));
        SwingUtilities.invokeAndWait(() -> {
            EvidenceManagerTab tab = new EvidenceManagerTab(orchestrator, config, api);
            List<JButton> buttons = buttons(tab.getUiComponent());
            for (JButton button : buttons) {
                String text = button.getText() == null ? "" : button.getText();
                check(!text.equals(I18n.t("ui.tab.results.btn.preview")), "evidence tab still has Preview");
                check(!text.equals(I18n.t("ui.tab.results.btn.generate_html")), "evidence tab still has Generate HTML");
                check(!text.equals(I18n.t("ui.tab.results.btn.export_pdf")), "evidence tab still has Export PDF");
            }
        });
    }

    private static List<JButton> buttonsNamed(Component root, String text) {
        List<JButton> matched = new ArrayList<>();
        for (JButton button : buttons(root)) {
            if (text.equals(button.getText())) matched.add(button);
        }
        return matched;
    }

    private static List<JButton> buttons(Component root) {
        List<JButton> out = new ArrayList<>();
        walk(root, out);
        return out;
    }

    private static void walk(Component c, List<JButton> out) {
        if (c instanceof JButton button) out.add(button);
        if (c instanceof Container box) {
            for (Component child : box.getComponents()) walk(child, out);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static Object stub(Class<?> iface) {
        return Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[]{iface}, (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "toString" -> iface.getSimpleName();
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> null;
                };
            }
            String name = method.getName();
            if ("currentTheme".equals(name)) return Theme.LIGHT;
            Class<?> rt = method.getReturnType();
            if (rt == void.class) return null;
            if (rt == boolean.class) return false;
            if (rt == int.class || rt == short.class || rt == byte.class) return 0;
            if (rt == long.class) return 0L;
            if (rt == float.class) return 0f;
            if (rt == double.class) return 0d;
            if (rt == String.class) return "";
            if (rt == Theme.class) return Theme.LIGHT;
            if (Component.class.isAssignableFrom(rt)) return new JPanel();
            if (rt == Frame.class || JFrame.class.isAssignableFrom(rt)) return new JFrame();
            if (rt.isInterface()) return stub(rt);
            if (List.class.isAssignableFrom(rt)) return List.of();
            if (Optional.class.isAssignableFrom(rt)) return Optional.empty();
            if (rt.isEnum()) return rt.getEnumConstants()[0];
            return null;
        });
    }
}
