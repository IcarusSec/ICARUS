import java.io.*;

public class Main2 {
    public static void main(String[] args) throws Exception {
        File file = new File("icarus-extension/src/main/java/icarus/ui/ReportingSettingsTab.java");
        byte[] bytes = new byte[(int) file.length()];
        try (FileInputStream fis = new FileInputStream(file)) {
            fis.read(bytes);
        }
        String content = new String(bytes);
        
        // 1. Replace fields
        content = content.replace("private DefaultTableModel sectionsTableModel;", "private com.icarus.ui.reportprofile.sections.SectionListPanel sectionListPanel;");
        content = content.replace("private JTable sectionsTable;", "private com.icarus.ui.reportprofile.sections.DetailPane detailPane;");
        
        // 2. Replace buildContent layout logic
        String oldFlow = "JPanel tableRow = createSectionsTable();\n        JPanel detailPanel = createDetailPanel();\n        flowSection = new SectionFlowPanel(tableRow, detailPanel);";
        String newFlow = "sectionListPanel = new com.icarus.ui.reportprofile.sections.SectionListPanel();\n        detailPane = new com.icarus.ui.reportprofile.sections.DetailPane();\n        bindSectionsFlow();\n        flowSection = new com.icarus.ui.reportprofile.sections.SectionFlowPanel(sectionListPanel.component(), detailPane.component());";
        content = content.replace(oldFlow, newFlow);
        
        // 3. Remove inline createSectionsTable and createDetailPanel logic inside buildContent
        // Wait, looking at the grep earlier:
        // 199: sectionsTableModel = new DefaultTableModel...
        // 216: sectionsTable = new JTable...
        // They were NOT methods! They were inline blocks of code inside buildContent() or similar.
        // Actually, let's just wipe lines 190 to 315 precisely by matching.
        // Let's print out the exact file structure so we can use string replace safely.
    }
}
