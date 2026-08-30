import java.io.*;

public class Main3 {
    public static void main(String[] args) throws Exception {
        File file = new File("icarus-extension/src/main/java/icarus/ui/ReportingSettingsTab.java");
        byte[] bytes = new byte[(int) file.length()];
        try (FileInputStream fis = new FileInputStream(file)) {
            fis.read(bytes);
        }
        String content = new String(bytes);
        
        // Let's migrate it cleanly
        content = content.replace("private DefaultTableModel sectionsTableModel;", "private com.icarus.ui.reportprofile.sections.SectionListPanel sectionListPanel;");
        content = content.replace("private JTable sectionsTable;", "private com.icarus.ui.reportprofile.sections.DetailPane detailPane;");
        
        // Remove buildContent JTable stuff
        content = content.replaceAll("(?s)sectionsTableModel = new DefaultTableModel.*?tableScroll = new JScrollPane\\(sectionsTable\\);", "sectionListPanel = new com.icarus.ui.reportprofile.sections.SectionListPanel();\n        detailPane = new com.icarus.ui.reportprofile.sections.DetailPane();\n        bindSectionsFlow();\n        JComponent tableScroll = sectionListPanel.component();");
        
        content = content.replace("sectionsTable.getSelectionModel().addListSelectionListener(e -> {", "/*");
        content = content.replaceAll("(?s)Map<String, String> params = \\(Map<String, String>\\) sectionsTableModel\\.getValueAt\\(r, 4\\);.*?params\\.put\\(\"content\", txtContent\\.getText\\(\\)\\);\n                    \\}\n                \\}\n            \\}\n        \\}\\);", "*/");
        
        // Create bindSectionsFlow
        String bindFlow = """
    private void bindSectionsFlow() {
        sectionListPanel.list.addListSelectionListener(e -> {
            if (e.getValueIsAdjusting() || isUpdatingUi) return;
            icarus.report.model.SectionNode node = sectionListPanel.list.getSelectedValue();
            if (node != null) {
                isUpdatingUi = true;
                detailPane.titleField.setText(node.params().getOrDefault("title", icarus.evidence.EvidenceUiHelpers.titleCase(node.id())));
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
        
        // DetailPanel swap
        content = content.replace("detailPanel.add(inlineLabel(\"Title:\", txtTitle), BorderLayout.NORTH);\n        detailPanel.add(contentScroll, BorderLayout.CENTER);", "detailPanel.add(detailPane.component(), BorderLayout.CENTER);");
        
        // loadProfileIntoForm
        content = content.replaceAll("(?s)sectionsTableModel\\.setRowCount\\(0\\);\n.*?new HashMap<>\\(n\\.params\\(\\)\\)\\}\\);", 
            "sectionListPanel.model.clear();\n        for (icarus.report.model.SectionNode n : p.sections().nodes()) sectionListPanel.model.addElement(n);");
            
        // saveCurrentProfileChanges
        content = content.replaceAll("(?s)for \\(int i = 0; i < sectionsTableModel\\.getRowCount\\(\\); i\\+\\+\\) \\{.*?oldParams != null \\? new HashMap<>\\(oldParams\\) : new HashMap<>\\(\\)\n            \\)\\);\n        \\}",
            "for (int i = 0; i < sectionListPanel.model.getSize(); i++) {\n            icarus.report.model.SectionNode node = sectionListPanel.model.getElementAt(i);\n            nodes.add(new icarus.report.model.SectionNode(node.id(), node.enabled(), i + 1, node.required(), node.rendererKey(), node.params()));\n        }");
            
        // moveSectionRow
        content = content.replace("int i = sectionsTable.getSelectedRow();", "int i = sectionListPanel.list.getSelectedIndex();");
        content = content.replace("if (t < 0 || t >= sectionsTableModel.getRowCount()) return;", "if (t < 0 || t >= sectionListPanel.model.getSize()) return;");
        content = content.replace("sectionsTable.setRowSelectionInterval(i, i);", "sectionListPanel.list.setSelectedIndex(i);");
        content = content.replace("sectionsTableModel.moveRow(i, i, t);", "icarus.report.model.SectionNode n = sectionListPanel.model.remove(i);\n        sectionListPanel.model.add(t, n);");
        content = content.replace("sectionsTable.setRowSelectionInterval(t, t);", "sectionListPanel.list.setSelectedIndex(t);");
        
        // addSection
        content = content.replaceAll("int nextOrder = sectionsTableModel\\.getRowCount\\(\\) \\+ 1;\n.*?sectionsTableModel\\.addRow\\(new Object\\[\\]\\{true, nextOrder, name\\.trim\\(\\)\\.toUpperCase\\(\\)\\.replace\\(' ', '_'\\), false, new HashMap<String, String>\\(\\)\\}\\);",
            "int nextOrder = sectionListPanel.model.getSize() + 1;\n            sectionListPanel.model.addElement(new icarus.report.model.SectionNode(name.trim().toUpperCase().replace(' ', '_'), true, nextOrder, false, name.trim().toUpperCase().replace(' ', '_'), new java.util.HashMap<>()));");
            
        // removeSection
        content = content.replace("int idx = sectionsTable.getSelectedRow();", "int idx = sectionListPanel.list.getSelectedIndex();");
        content = content.replace("Boolean required = (Boolean) sectionsTableModel.getValueAt(idx, 3);", "Boolean required = sectionListPanel.model.getElementAt(idx).required();");
        content = content.replaceAll("(?s)sectionsTableModel\\.removeRow\\(idx\\);\n.*?sectionsTableModel\\.setValueAt\\(i \\+ 1, i, 1\\);\n        \\}", "sectionListPanel.model.remove(idx);");

        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(content.getBytes());
        }
    }
}
