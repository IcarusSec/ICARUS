import re

with open("icarus-extension/src/main/java/icarus/ui/ReportingSettingsTab.java", "r") as f:
    content = f.read()

# Delete createSectionsTable
content = re.sub(r'private JPanel createSectionsTable\(\) \{.*?(?=private JPanel createDetailPanel)', '', content, flags=re.DOTALL)
# Delete createDetailPanel
content = re.sub(r'private JPanel createDetailPanel\(\) \{.*?(?=private void refreshProfileList)', '', content, flags=re.DOTALL)

# Also fix the leftover usages of sectionsTable in moveSection
content = re.sub(r'int i = sectionsTable\.getSelectedRow\(\);', 'int i = sectionListPanel.list.getSelectedIndex();', content)
content = re.sub(r'sectionsTable\.setRowSelectionInterval\(i, i\);', 'sectionListPanel.list.setSelectedIndex(i);', content)

with open("icarus-extension/src/main/java/icarus/ui/ReportingSettingsTab.java", "w") as f:
    f.write(content)
