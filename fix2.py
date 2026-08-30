import sys

with open('icarus-extension/src/main/java/icarus/ui/ReportingSettingsTab.java', 'r') as f:
    content = f.read()

content = content.replace("sectionListPanel.component().add(sideBtns, BorderLayout.EAST);", "((JPanel) sectionListPanel.component()).add(sideBtns, BorderLayout.EAST);")
content = content.replace("flowPanel = new SectionFlowPanel(sectionListPanel.component(), detailPane.component());", "flowPanel = new SectionFlowPanel((JComponent) sectionListPanel.component(), (JComponent) detailPane.component());")

content = content.replace(".setSelected(", ".setOn(")
content = content.replace(".isSelected()", ".isOn()")

with open('icarus-extension/src/main/java/icarus/ui/ReportingSettingsTab.java', 'w') as f:
    f.write(content)
