package icarus.ui.evidence;

import burp.api.montoya.MontoyaApi;
import icarus.Orchestrator;
import icarus.autoauth.AutoAuthModule;
import icarus.core.Finding;
import icarus.core.I18n;
import icarus.core.ModuleConfig;
import icarus.core.Severity;
import icarus.evidence.EvidenceCapture;
import icarus.evidence.ReportGenerator;

import javax.imageio.ImageIO;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.text.BadLocationException;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Headless Swing checks for the Evidence tab. Run with:
 * {@code java -cp build_manual/classes:libs/montoya-api-2026.7.jar icarus.ui.evidence.EvidenceManagerUiTest}
 */
public final class EvidenceManagerUiTest {

    public static void main(String[] args) throws Exception {
        captionKeepsFocusAcrossKeystrokes();
        removingEvidenceStillRefreshesTheDetail();
        System.out.println("EvidenceManagerUiTest: all checks passed.");
    }

    /**
     * Typing in the caption used to save on every keystroke, which notified the evidence
     * listener, which rebuilt the detail panel and dropped keyboard focus. Only the first
     * character survived. Pasting a whole string in one document event still worked.
     */
    private static void captionKeepsFocusAcrossKeystrokes() throws Exception {
        Fixture fx = Fixture.create("Original title", "");
        SwingUtilities.invokeAndWait(() -> {
            JTextArea caption = find(fx.tab.getUiComponent(), JTextArea.class).get(0);
            JTextArea before = caption;
            try {
                for (char c : "Hello".toCharArray()) {
                    caption.getDocument().insertString(caption.getDocument().getLength(), String.valueOf(c), null);
                }
            } catch (BadLocationException e) {
                throw new AssertionError(e);
            }
            JTextArea after = find(fx.tab.getUiComponent(), JTextArea.class).get(0);
            check(before == after, "caption component was replaced after typing; focus would be lost");
            check("Hello".equals(after.getText()), "caption field text was '" + after.getText() + "', expected Hello");
            check("Hello".equals(fx.evidence.getCaptured().get(0).caption()),
                    "stored caption was '" + fx.evidence.getCaptured().get(0).caption() + "'");
            check(fx.evidenceNotices.get() == 5,
                    "caption edits must still notify evidence listeners (autosave), got " + fx.evidenceNotices.get());
        });
    }

    /** A real change to the screenshot list (not just caption text) still rebuilds the detail. */
    private static void removingEvidenceStillRefreshesTheDetail() throws Exception {
        Fixture fx = Fixture.create("Original title", "keep me");
        SwingUtilities.invokeAndWait(() -> {
            check(!find(fx.tab.getUiComponent(), JTextArea.class).isEmpty(), "caption should be visible before removal");
            fx.evidence.removeCaptured(fx.evidence.getCaptured().get(0));
            check(find(fx.tab.getUiComponent(), JTextArea.class).isEmpty(),
                    "removing a screenshot should rebuild the detail panel");
        });
    }

    static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    static <T> List<T> find(Component root, Class<T> type) {
        List<T> out = new ArrayList<>();
        walk(root, type, out);
        return out;
    }

    private static <T> void walk(Component c, Class<T> type, List<T> out) {
        if (type.isInstance(c)) out.add(type.cast(c));
        if (c instanceof Container box) {
            for (Component child : box.getComponents()) walk(child, type, out);
        }
    }

    /** One finding with one screenshot, and the Evidence tab built on top of it. */
    static final class Fixture {
        final EvidenceCapture evidence;
        final Orchestrator orchestrator;
        final ModuleConfig config;
        final MontoyaApi api;
        final EvidenceManagerTab tab;
        final AtomicInteger evidenceNotices = new AtomicInteger();
        final Path imagePath;

        private Fixture(EvidenceCapture evidence, Orchestrator orchestrator, ModuleConfig config, MontoyaApi api,
                        EvidenceManagerTab tab, Path imagePath) {
            this.evidence = evidence;
            this.orchestrator = orchestrator;
            this.config = config;
            this.api = api;
            this.tab = tab;
            this.imagePath = imagePath;
        }

        static Fixture create(String title, String caption) throws Exception {
            MontoyaApi api = (MontoyaApi) stub(MontoyaApi.class);
            ModuleConfig config = new ModuleConfig();
            I18n.initialize(config);
            EvidenceCapture evidence = new EvidenceCapture(api, config);
            Orchestrator orchestrator = new Orchestrator(api, List.of(), config, evidence,
                    new ReportGenerator(api), new AutoAuthModule(api, config));
            Path imagePath = Files.createTempFile("icarus-evidence-", ".png");
            imagePath.toFile().deleteOnExit();
            BufferedImage img = new BufferedImage(80, 48, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = img.createGraphics();
            g.setColor(new Color(30, 36, 48));
            g.fillRect(0, 0, 80, 48);
            g.dispose();
            ImageIO.write(img, "png", imagePath.toFile());
            Finding finding = Finding.builder("ParamValidator", title)
                    .description("reflected in the body")
                    .severity(Severity.MEDIUM)
                    .path("query:q")
                    .build();
            evidence.restoreCaptured(new EvidenceCapture.CapturedEvidence(finding, imagePath, img, caption), true);
            orchestrator.updateFinding(finding);
            Fixture[] holder = new Fixture[1];
            SwingUtilities.invokeAndWait(() -> {
                EvidenceManagerTab tab = new EvidenceManagerTab(orchestrator, config, api);
                holder[0] = new Fixture(evidence, orchestrator, config, api, tab, imagePath);
            });
            // Count notices after the tab is built so construction-time restores are ignored.
            evidence.addChangeListener(holder[0].evidenceNotices::incrementAndGet);
            return holder[0];
        }
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
            Class<?> rt = method.getReturnType();
            if (rt == void.class) return null;
            if (rt == boolean.class) return false;
            if (rt == int.class || rt == short.class || rt == byte.class) return 0;
            if (rt == long.class) return 0L;
            if (rt == float.class) return 0f;
            if (rt == double.class) return 0d;
            if (rt == String.class) return null;
            if (rt.isInterface()) return stub(rt);
            if (List.class.isAssignableFrom(rt)) return List.of();
            return null;
        });
    }
}
