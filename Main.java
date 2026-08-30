import java.io.*;
import java.util.regex.*;

public class Main {
    public static void main(String[] args) throws Exception {
        File file = new File("icarus-extension/src/main/java/icarus/ui/ReportingSettingsTab.java");
        byte[] bytes = new byte[(int) file.length()];
        try (FileInputStream fis = new FileInputStream(file)) {
            fis.read(bytes);
        }
        String content = new String(bytes);
        
        // Let's do string replacement one by one for exact matches!
        
        content = content.replace("private DefaultTableModel sectionsTableModel;", "private com.icarus.ui.reportprofile.sections.SectionListPanel sectionListPanel;");
        content = content.replace("private JTable sectionsTable;", "private com.icarus.ui.reportprofile.sections.DetailPane detailPane;");
        
        content = content.replace(
            "JPanel tableRow = createSectionsTable();\n        JPanel detailPanel = createDetailPanel();\n        flowSection = new SectionFlowPanel(tableRow, detailPanel);",
            "sectionListPanel = new com.icarus.ui.reportprofile.sections.SectionListPanel();\n        detailPane = new com.icarus.ui.reportprofile.sections.DetailPane();\n        bindSectionsFlow();\n        flowSection = new com.icarus.ui.reportprofile.sections.SectionFlowPanel(sectionListPanel.component(), detailPane.component());"
        );
        
        // Erase the old methods
        content = content.replaceAll("(?s)private JPanel createSectionsTable\\(\\).*?private void refreshProfileList", "private void refreshProfileList");
        content = content.replaceAll("(?s)private JPanel createDetailPanel\\(\\).*?// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n    //  Data: load / save", "// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n    //  Data: load / save");
        
        String bindFlow = """
    private void bindSectionsFlow() {
        sectionListPanel.list.addListSelectionListener(e -> {
            if (e.getValueIsAdjusting() || isUpdatingUi) return;
            icarus.report.model.SectionNode node = sectionListPanel.list.getSelectedValue();
            if (node != null) {
                isUpdatingUi = true;
                detailPane.titleField.setText(node.params().getOrDefault("title", titleCase(node.id())));
                detailPane.titleField.setCaretPosition(0);
                detailPane.bodyWell.setText(node.params().getOrDefault("content", ""));
                
                boolean disable = "EXECUTIVE_SUMMARY".equals(node.id()) && (currentProfile != null && currentProfile.builtIn());
                detailPane.titleField.setEnabled(!disable);
                detailPane.bodyWell.setEnabled(!disable);
                isUpdatingUi = false;
            } else {
                isUpdatingUi = true;
                detailPane.titleField.setText("");
                detailPane.bodyWell.setText("");
                detailPane.titleField.setEnabled(false);
                detailPane.bodyWell.setEnabled(false);
                isUpdatingUi = false;
            }
        });

        javax.swing.event.DocumentListener dl = new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { update(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { update(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { update(); }
            private void update() {
                if (isUpdatingUi) return;
                int idx = sectionListPanel.list.getSelectedIndex();
                if (idx >= 0) {
                    if (currentProfile != null && currentProfile.builtIn()) {
                        autoCloneProfile();
                    } else {
                        icarus.report.model.SectionNode node = sectionListPanel.model.getElementAt(idx);
                        java.util.Map<String, String> newParams = new java.util.HashMap<>(node.params());
                        newParams.put("title", detailPane.titleField.getText());
                        newParams.put("content", detailPane.bodyWell.getText());
                        icarus.report.model.SectionNode updated = new icarus.report.model.SectionNode(node.id(), node.enabled(), node.order(), node.required(), node.rendererKey(), newParams);
                        sectionListPanel.model.setElementAt(updated, idx);
                    }
                }
            }
        };
        detailPane.titleField.getDocument().addDocumentListener(dl);
        detailPane.bodyWell.getDocument().addDocumentListener(dl);
    }
""";

        content = content.replace("private void refreshProfileList", bindFlow + "\n    private void refreshProfileList");
        
        // Loading
        content = content.replaceAll("sectionsTableModel\\.setRowCount\\(0\\);\n.*?sectionsTableModel\\.addRow\\(new Object\\[\\]\\{n\\.enabled\\(\\), n\\.order\\(\\), n\\.id\\(\\), n\\.required\\(\\), new HashMap<>\\(n\\.params\\(\\)\\)\\}\\);", 
            "sectionListPanel.model.clear();\n        for (icarus.report.model.SectionNode n : p.sections().nodes())\n            sectionListPanel.model.addElement(n);");
            
        // Saving
        content = content.replaceAll("(?s)List<SectionNode> nodes = new ArrayList<>\\(\\);\n.*?sectionsTableModel\\.getValueAt\\(i, 3\\)\\)\\);\n        \\}", 
            "List<icarus.report.model.SectionNode> nodes = new java.util.ArrayList<>();\n        for (int i = 0; i < sectionListPanel.model.getSize(); i++) {\n            icarus.report.model.SectionNode node = sectionListPanel.model.getElementAt(i);\n            nodes.add(new icarus.report.model.SectionNode(node.id(), node.enabled(), i + 1, node.required(), node.rendererKey(), node.params()));\n        }");
        
        // moveSection
        content = content.replace("int i = sectionsTable.getSelectedRow();", "int i = sectionListPanel.list.getSelectedIndex();");
        content = content.replace("if (t < 0 || t >= sectionsTableModel.getRowCount()) return;", "if (t < 0 || t >= sectionListPanel.model.getSize()) return;");
        content = content.replace("sectionsTable.setRowSelectionInterval(i, i);", "sectionListPanel.list.setSelectedIndex(i);");
        content = content.replace("sectionsTableModel.moveRow(i, i, t);", "icarus.report.model.SectionNode n = sectionListPanel.model.remove(i);\n        sectionListPanel.model.add(t, n);");
        content = content.replace("sectionsTable.setRowSelectionInterval(t, t);", "sectionListPanel.list.setSelectedIndex(t);");
        
        // addSection
        content = content.replaceAll("int nextOrder = sectionsTableModel\\.getRowCount\\(\\) \\+ 1;\n.*?sectionsTableModel\\.addRow\\(new Object\\[\\]\\{true, nextOrder, name\\.trim\\(\\)\\.toUpperCase\\(\\)\\.replace\\(' ', '_'\\), false, new HashMap<String, String>\\(\\)\\}\\);",
            "int nextOrder = sectionListPanel.model.getSize() + 1;\n            sectionListPanel.model.addElement(new icarus.report.model.SectionNode(name.trim().toUpperCase().replace(' ', '_'), true, nextOrder, false, name.trim().toUpperCase().replace(' ', '_'), new java.util.HashMap<>()));");
            
        // removeSection
        content = content.replace("Boolean required = (Boolean) sectionsTableModel.getValueAt(idx, 3);", "Boolean required = sectionListPanel.model.getElementAt(idx).required();");
        content = content.replaceAll("(?s)sectionsTableModel\\.removeRow\\(idx\\);\n.*?sectionsTableModel\\.setValueAt\\(i \\+ 1, i, 1\\);\n        \\}",
            "sectionListPanel.model.remove(idx);");
            
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(content.getBytes());
        }
    }
}
