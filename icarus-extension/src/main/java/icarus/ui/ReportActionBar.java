package icarus.ui;

import icarus.core.I18n;

import javax.swing.JButton;
import javax.swing.JPanel;
import java.awt.FlowLayout;
import java.util.function.Consumer;

/**
 * The single place Preview, Generate HTML, and Export PDF live.
 * One bar sits on the shell, so every tab reaches the same three actions.
 * Buttons use the shared scale and the host look and feel.
 */
public final class ReportActionBar extends JPanel {

    public static final String NAME = "report-action-bar";

    private final JButton preview;
    private final JButton generateHtml;
    private final JButton exportPdf;

    public ReportActionBar(Consumer<JButton> onPreview,
                           Consumer<JButton> onGenerateHtml,
                           Consumer<JButton> onExportPdf) {
        super(new FlowLayout(FlowLayout.RIGHT, UiMetrics.SM, 0));
        setName(NAME);
        setBorder(UiMetrics.pad(UiMetrics.XS, UiMetrics.LG, UiMetrics.SM, UiMetrics.LG));

        preview = labeled(I18n.t("ui.tab.results.btn.preview"), "report-action-preview", onPreview);
        generateHtml = labeled(I18n.t("ui.tab.results.btn.generate_html"), "report-action-html", onGenerateHtml);
        exportPdf = labeled(I18n.t("ui.tab.results.btn.export_pdf"), "report-action-pdf", onExportPdf);

        add(preview);
        add(generateHtml);
        add(exportPdf);
    }

    public JButton previewButton() {
        return preview;
    }

    public JButton generateHtmlButton() {
        return generateHtml;
    }

    public JButton exportPdfButton() {
        return exportPdf;
    }

    private static JButton labeled(String text, String name, Consumer<JButton> action) {
        JButton button = UiMetrics.commandButton(text, null);
        button.setName(name);
        button.addActionListener(e -> action.accept(button));
        return button;
    }
}
