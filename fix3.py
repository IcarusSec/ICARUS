import sys

with open('icarus-extension/src/main/java/icarus/ui/ReportingSettingsTab.java', 'r') as f:
    content = f.read()

bad_code = """        ((JPanel) sectionListPanel.component()).add(sideBtns, BorderLayout.EAST);
        
        flowPanel = new SectionFlowPanel((JComponent) sectionListPanel.component(), (JComponent) detailPane.component());"""

good_code = """        JPanel listWrapper = new JPanel(new BorderLayout());
        listWrapper.setOpaque(false);
        listWrapper.add(sectionListPanel.component(), BorderLayout.CENTER);
        listWrapper.add(sideBtns, BorderLayout.EAST);
        
        flowPanel = new SectionFlowPanel(listWrapper, (JComponent) detailPane.component());"""

content = content.replace(bad_code, good_code)

with open('icarus-extension/src/main/java/icarus/ui/ReportingSettingsTab.java', 'w') as f:
    f.write(content)
