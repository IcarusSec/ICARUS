package icarus.ui;

import icarus.Orchestrator;
import icarus.autoauth.AutoAuthModule;
import icarus.core.I18n;
import icarus.core.ModuleConfig;
import icarus.evidence.EvidenceCapture;
import icarus.evidence.ReportGenerator;
import icarus.ui.evidence.EvidenceManagerTab;

import burp.api.montoya.MontoyaApi;

import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

/**
 * Empty panels explain the next step instead of rendering blank.
 * {@code java -cp build_manual/classes:libs/montoya-api-2026.7.jar icarus.ui.EmptyStateTest}
 */
public final class EmptyStateTest {

    public static void main(String[] args) throws Exception {
        messageIsVisible();
        emptyEvidenceExplainsTheNextStep();
        System.out.println("EmptyStateTest: all checks passed.");
    }

    private static void messageIsVisible() {
        EmptyState state = new EmptyState("Capture a screenshot, then use Add to report.");
        check(state.shownHtml().contains("Add to report"), "empty state dropped the message: " + state.shownHtml());
        check(state.getComponentCount() == 1, "empty state should be a single explanation");
    }

    private static void emptyEvidenceExplainsTheNextStep() throws Exception {
        MontoyaApi api = (MontoyaApi) stub(MontoyaApi.class);
        ModuleConfig config = new ModuleConfig();
        I18n.initialize(config);
        EvidenceCapture evidence = new EvidenceCapture(api, config);
        Orchestrator orchestrator = new Orchestrator(api, List.of(), config, evidence,
                new ReportGenerator(api), new AutoAuthModule(api, config));
        SwingUtilities.invokeAndWait(() -> {
            EvidenceManagerTab tab = new EvidenceManagerTab(orchestrator, config, api);
            String blob = labels(tab.getUiComponent()).stream().map(JLabel::getText).reduce("", (a, b) -> a + "\n" + b);
            check(blob.contains("Add to report") || blob.contains(I18n.t("ui.evidence.empty.captured")),
                    "empty evidence tab did not explain what to do next: " + blob);
        });
    }

    private static List<JLabel> labels(Component root) {
        List<JLabel> out = new ArrayList<>();
        walk(root, out);
        return out;
    }

    private static void walk(Component c, List<JLabel> out) {
        if (c instanceof JLabel label) out.add(label);
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
            Class<?> rt = method.getReturnType();
            if (rt == void.class) return null;
            if (rt == boolean.class) return false;
            if (rt == int.class || rt == short.class || rt == byte.class) return 0;
            if (rt == long.class) return 0L;
            if (rt == float.class) return 0f;
            if (rt == double.class) return 0d;
            if (rt == String.class) return "";
            if (rt.isInterface()) return stub(rt);
            if (List.class.isAssignableFrom(rt)) return List.of();
            return null;
        });
    }
}
