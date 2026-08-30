import re

with open("icarus-extension/src/main/java/icarus/ui/ReportingSettingsTab.java", "r") as f:
    content = f.read()

# Make sure we don't break the whole file!
content = re.sub(r'private JPanel createSectionsTable\(\) \{.*?(?=private JPanel createDetailPanel\(\) \{)', '', content, flags=re.DOTALL)
content = re.sub(r'private JPanel createDetailPanel\(\) \{.*?(?=// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n\s*//  Data: load / save)', '', content, flags=re.DOTALL)

with open("icarus-extension/src/main/java/icarus/ui/ReportingSettingsTab.java", "w") as f:
    f.write(content)
