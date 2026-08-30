import java.nio.file.Files;
import java.nio.file.Paths;

public class Main4 {
    public static void main(String[] args) throws Exception {
        String code = new String(Files.readAllBytes(Paths.get("icarus-extension/src/main/java/icarus/ui/ReportingSettingsTab.java")));

        code = code.replace("private DefaultTableModel sectionsTableModel;", "private com.icarus.ui.reportprofile.sections.SectionListPanel sectionListPanel;");
        code = code.replace("private JTable sectionsTable;", "private com.icarus.ui.reportprofile.sections.DetailPane detailPane;");

        String layoutOld = """
        sectionsTableModel = new DefaultTableModel(new Object[]{"On", "#", "Section", "Req", "Params"}, 0) {
            @Override public Class<?> getColumnClass(int c) {
                if (c == 0 || c == 3) return Boolean.class;
                if (c == 1) return Integer.class;
                if (c == 4) return Map.class;
                return String.class;
            }
            @Override public boolean isCellEditable(int r, int c) {
                if (currentProfile != null && currentProfile.builtIn()) return false;
                if (c == 0) {
                    Boolean req = (Boolean) getValueAt(r, 3);
                    return req == null || !req;
                }
                if (c == 2) return true; // Section ID editable
                return false;
            }
        };
        sectionsTable = new JTable(sectionsTableModel);
        sectionsTable.setRowHeight(24);
        sectionsTable.getColumnModel().getColumn(0).setMaxWidth(40);
        sectionsTable.getColumnModel().getColumn(1).setMaxWidth(30);
        sectionsTable.getColumnModel().getColumn(3).setMaxWidth(40);
        // Hide the Params column
        sectionsTable.getColumnModel().removeColumn(sectionsTable.getColumnModel().getColumn(4));
        sectionsTable.setFillsViewportHeight(true);

        JScrollPane tableScroll = new JScrollPane(sectionsTable);
""";
        code = code.replace(layoutOld, """
        sectionListPanel = new com.icarus.ui.reportprofile.sections.SectionListPanel();
        detailPane = new com.icarus.ui.reportprofile.sections.DetailPane();
        bindSectionsFlow();
        javax.swing.JComponent tableScroll = sectionListPanel.component();
""");

        String selectionListener = """
        sectionsTable.getSelectionModel().addListSelectionListener(e -> {
            if (e.getValueIsAdjusting() || isUpdatingUi) return;
            int r = sectionsTable.getSelectedRow();
            if (r >= 0) {
                @SuppressWarnings("unchecked")
                Map<String, String> params = (Map<String, String>) sectionsTableModel.getValueAt(r, 4); // hidden col index is still 4 in model
                String id = (String) sectionsTableModel.getValueAt(r, 2);
                
                isUpdatingUi = true;
                txtTitle.setText(params.getOrDefault("title", EvidenceUiHelpers.titleCase(id)));
                txtTitle.setCaretPosition(0);
                txtContent.setText(params.getOrDefault("content", ""));
                
                // EXECUTIVE_SUMMARY cannot be renamed
                boolean disable = "EXECUTIVE_SUMMARY".equals(id) && (currentProfile != null && currentProfile.builtIn());
                txtTitle.setEnabled(!disable);
                txtContent.setEnabled(!disable);
                
                isUpdatingUi = false;
            } else {
                isUpdatingUi = true;
                txtTitle.setText("");
                txtContent.setText("");
                txtTitle.setEnabled(false);
                txtContent.setEnabled(false);
                isUpdatingUi = false;
            }
        });
""";
        code = code.replace(selectionListener, "");

        String documentListener = """
        javax.swing.event.DocumentListener dl = new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { update(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { update(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { update(); }
            private void update() {
                if (isUpdatingUi) return;
                int r = sectionsTable.getSelectedRow();
                if (r >= 0) {
                    if (currentProfile != null && currentProfile.builtIn()) {
                        autoCloneProfile();
                    } else {
                        @SuppressWarnings("unchecked")
                        Map<String, String> params = (Map<String, String>) sectionsTableModel.getValueAt(r, 4);
                        params.put("title", txtTitle.getText());
                        params.put("content", txtContent.getText());
                    }
                }
            }
        };
        txtTitle.getDocument().addDocumentListener(dl);
        txtContent.getDocument().addDocumentListener(dl);
""";
        code = code.replace(documentListener, "");

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
        code = code.replace("private void refreshProfileList", bindFlow + "\n    private void refreshProfileList");

        code = code.replace("detailPanel.add(inlineLabel(\"Title:\", txtTitle), BorderLayout.NORTH);\n        detailPanel.add(contentScroll, BorderLayout.CENTER);", "detailPanel.add(detailPane.component(), BorderLayout.CENTER);");

        String loadOld = """
        sectionsTableModel.setRowCount(0);
        for (SectionNode n : p.sections().nodes())
            sectionsTableModel.addRow(new Object[]{n.enabled(), n.order(), n.id(), n.required(), new HashMap<>(n.params())});
""";
        code = code.replace(loadOld, "sectionListPanel.model.clear();\n        for (SectionNode n : p.sections().nodes()) sectionListPanel.model.addElement(n);\n");

        String saveOld = """
        for (int i = 0; i < sectionsTableModel.getRowCount(); i++) {
            @SuppressWarnings("unchecked")
            Map<String, String> oldParams = (Map<String, String>) sectionsTableModel.getValueAt(i, 4);
            nodes.add(SectionNode.of(
                (String) sectionsTableModel.getValueAt(i, 2),
                (Boolean) sectionsTableModel.getValueAt(i, 0),
                i + 1,
                (Boolean) sectionsTableModel.getValueAt(i, 3),
                null,
                oldParams != null ? new HashMap<>(oldParams) : new HashMap<>()
            ));
        }
""";
        code = code.replace(saveOld, "        for (int i = 0; i < sectionListPanel.model.getSize(); i++) {\n            SectionNode node = sectionListPanel.model.getElementAt(i);\n            nodes.add(new SectionNode(node.id(), node.enabled(), i + 1, node.required(), node.rendererKey(), node.params()));\n        }\n");

        String moveOld = """
    private void moveSectionRow(int delta) {
        int i = sectionsTable.getSelectedRow();
        if (i < 0) return;
        int t = i + delta;
        if (t < 0 || t >= sectionsTableModel.getRowCount()) return;
        
        if (currentProfile != null && currentProfile.builtIn()) {
            autoCloneProfile();
            // Restore selection after model reload
            sectionsTable.setRowSelectionInterval(i, i);
        }
        
        sectionsTableModel.moveRow(i, i, t);
        sectionsTable.setRowSelectionInterval(t, t);
    }
""";
        code = code.replace(moveOld, """
    private void moveSectionRow(int delta) {
        int i = sectionListPanel.list.getSelectedIndex();
        if (i < 0) return;
        int t = i + delta;
        if (t < 0 || t >= sectionListPanel.model.getSize()) return;
        if (currentProfile != null && currentProfile.builtIn()) {
            autoCloneProfile();
            sectionListPanel.list.setSelectedIndex(i);
        }
        SectionNode n = sectionListPanel.model.remove(i);
        sectionListPanel.model.add(t, n);
        sectionListPanel.list.setSelectedIndex(t);
    }
""");

        String addOld = """
    private void addSection() {
        String name = JOptionPane.showInputDialog(containerPanel, "Section identifier (e.g. CUSTOM_NOTES):");
        if (name != null && !name.isBlank()) {
            if (currentProfile != null && currentProfile.builtIn()) {
                autoCloneProfile();
            }
            int nextOrder = sectionsTableModel.getRowCount() + 1;
            sectionsTableModel.addRow(new Object[]{true, nextOrder, name.trim().toUpperCase().replace(' ', '_'), false, new HashMap<String, String>()});
        }
    }
""";
        code = code.replace(addOld, """
    private void addSection() {
        String name = JOptionPane.showInputDialog(containerPanel, "Section identifier (e.g. CUSTOM_NOTES):");
        if (name != null && !name.isBlank()) {
            if (currentProfile != null && currentProfile.builtIn()) {
                autoCloneProfile();
            }
            int nextOrder = sectionListPanel.model.getSize() + 1;
            sectionListPanel.model.addElement(new SectionNode(name.trim().toUpperCase().replace(' ', '_'), true, nextOrder, false, name.trim().toUpperCase().replace(' ', '_'), new java.util.HashMap<>()));
        }
    }
""");

        String rmOld = """
    private void removeSection() {
        int idx = sectionsTable.getSelectedRow();
        if (idx < 0) return;
        Boolean required = (Boolean) sectionsTableModel.getValueAt(idx, 3);
        if (required != null && required) {
            JOptionPane.showMessageDialog(containerPanel, "Cannot remove a required section.", "Info", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        
        if (currentProfile != null && currentProfile.builtIn()) {
            autoCloneProfile();
        }
        
        sectionsTableModel.removeRow(idx);
        // Renumber
        for (int i = 0; i < sectionsTableModel.getRowCount(); i++) {
            sectionsTableModel.setValueAt(i + 1, i, 1);
        }
    }
""";
        code = code.replace(rmOld, """
    private void removeSection() {
        int idx = sectionListPanel.list.getSelectedIndex();
        if (idx < 0) return;
        Boolean required = sectionListPanel.model.getElementAt(idx).required();
        if (required != null && required) {
            JOptionPane.showMessageDialog(containerPanel, "Cannot remove a required section.", "Info", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        if (currentProfile != null && currentProfile.builtIn()) {
            autoCloneProfile();
        }
        sectionListPanel.model.remove(idx);
    }
""");
        Files.write(Paths.get("icarus-extension/src/main/java/icarus/ui/ReportingSettingsTab.java"), code.getBytes());
    }
}
