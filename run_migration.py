import re

with open("icarus-extension/src/main/java/icarus/ui/ReportingSettingsTab.java", "r") as f:
    code = f.read()

# 1. Replace the fields
code = code.replace("private DefaultTableModel sectionsTableModel;", "private com.icarus.ui.reportprofile.sections.SectionListPanel sectionListPanel;")
code = code.replace("private JTable sectionsTable;", "private com.icarus.ui.reportprofile.sections.DetailPane detailPane;")

# 2. Replace the layout construction in buildContent()
layout_old = """        JPanel tableRow = createSectionsTable();
        JPanel detailPanel = createDetailPanel();
        flowSection = new SectionFlowPanel(tableRow, detailPanel);"""
layout_new = """        sectionListPanel = new com.icarus.ui.reportprofile.sections.SectionListPanel();
        detailPane = new com.icarus.ui.reportprofile.sections.DetailPane();
        bindSectionsFlow();
        flowSection = new com.icarus.ui.reportprofile.sections.SectionFlowPanel(sectionListPanel.component(), detailPane.component());"""
code = code.replace(layout_old, layout_new)

# 3. Create bindSectionsFlow() and erase old methods
bind_flow_code = """
    private void bindSectionsFlow() {
        sectionListPanel.list.addListSelectionListener(e -> {
            if (e.getValueIsAdjusting() || isUpdatingUi) return;
            icarus.report.model.SectionNode node = sectionListPanel.list.getSelectedValue();
            if (node != null) {
                isUpdatingUi = true;
                detailPane.titleField.setText(node.params().getOrDefault("title", icarus.ui.EvidenceUiHelpers.titleCase(node.id())));
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
"""

# Regex out createSectionsTable() and createDetailPanel() entirely
code = re.sub(r'private JPanel createSectionsTable\(\)\s*\{.*?(?=private void refreshProfileList)', bind_flow_code + "\n    ", code, flags=re.DOTALL)
# The above regex will catch createDetailPanel as well if it sits between createSectionsTable and refreshProfileList.

# 4. loadProfileIntoForm -> populate SectionListPanel
load_old = r'sectionsTableModel\.setRowCount\(0\);\s*for \(SectionNode n : p\.sections\(\)\.nodes\(\)\)\s*sectionsTableModel\.addRow\(new Object\[\]\{n\.enabled\(\), n\.order\(\), n\.id\(\), n\.required\(\), new HashMap<\>\(n\.params\(\)\)\}\);'
load_new = r'''sectionListPanel.model.clear();
        for (SectionNode n : p.sections().nodes()) {
            sectionListPanel.model.addElement(n);
        }'''
code = re.sub(load_old, load_new, code)

# 5. saveCurrentProfileChanges -> read SectionListPanel
save_old = r'for \(int i = 0; i < sectionsTableModel\.getRowCount\(\); i\+\+\) \{\s*@SuppressWarnings\("unchecked"\)\s*Map<String, String> oldParams = \(Map<String, String>\) sectionsTableModel\.getValueAt\(i, 4\);\s*nodes\.add\(SectionNode\.of\(\s*\(String\) sectionsTableModel\.getValueAt\(i, 2\),\s*\(Boolean\) sectionsTableModel\.getValueAt\(i, 0\),\s*i \+ 1,\s*\(Boolean\) sectionsTableModel\.getValueAt\(i, 3\),\s*null,\s*oldParams != null \? new HashMap<\>\(oldParams\) : new HashMap<\>\(\)\s*\)\);\s*\}'
save_new = r'''for (int i = 0; i < sectionListPanel.model.getSize(); i++) {
            SectionNode node = sectionListPanel.model.getElementAt(i);
            nodes.add(new SectionNode(node.id(), node.enabled(), i + 1, node.required(), node.rendererKey(), node.params()));
        }'''
code = re.sub(save_old, save_new, code)

# 6. Button actions
# moveSection
code = re.sub(r'if \(t < 0 \|\| t >= sectionsTableModel\.getRowCount\(\)\) return;', 'if (t < 0 || t >= sectionListPanel.model.getSize()) return;', code)
code = code.replace("int i = sectionsTable.getSelectedRow();", "int i = sectionListPanel.list.getSelectedIndex();")
code = code.replace("sectionsTable.setRowSelectionInterval(i, i);", "sectionListPanel.list.setSelectedIndex(i);")
code = code.replace("sectionsTableModel.moveRow(i, i, t);", "SectionNode n = sectionListPanel.model.remove(i);\n        sectionListPanel.model.add(t, n);")
code = code.replace("sectionsTable.setRowSelectionInterval(t, t);", "sectionListPanel.list.setSelectedIndex(t);")

# addSection
add_old = r'int nextOrder = sectionsTableModel\.getRowCount\(\) \+ 1;\s*sectionsTableModel\.addRow\(new Object\[\]\{true, nextOrder, name\.trim\(\)\.toUpperCase\(\)\.replace\(\' \', \'\_\'\), false, new HashMap<String, String>\(\)\}\);'
add_new = r'''int nextOrder = sectionListPanel.model.getSize() + 1;
            sectionListPanel.model.addElement(new SectionNode(name.trim().toUpperCase().replace(' ', '_'), true, nextOrder, false, name.trim().toUpperCase().replace(' ', '_'), new java.util.HashMap<>()));'''
code = re.sub(add_old, add_new, code)

# removeSection
code = re.sub(r'int idx = sectionsTable\.getSelectedRow\(\);', 'int idx = sectionListPanel.list.getSelectedIndex();', code)
code = re.sub(r'Boolean required = \(Boolean\) sectionsTableModel\.getValueAt\(idx, 3\);', 'Boolean required = sectionListPanel.model.getElementAt(idx).required();', code)
rm_old = r'sectionsTableModel\.removeRow\(idx\);\s*// Renumber\s*for \(int i = 0; i < sectionsTableModel\.getRowCount\(\); i\+\+\) \{\s*sectionsTableModel\.setValueAt\(i \+ 1, i, 1\);\s*\}'
rm_new = r'sectionListPanel.model.remove(idx);'
code = re.sub(rm_old, rm_new, code)

with open("icarus-extension/src/main/java/icarus/ui/ReportingSettingsTab.java", "w") as f:
    f.write(code)

print("Migration applied via regex.")
