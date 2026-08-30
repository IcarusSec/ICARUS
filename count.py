with open("icarus-extension/src/main/java/icarus/ui/ReportingSettingsTab.java") as f:
    code = f.read()

opens = code.count('{')
closes = code.count('}')
print(f"Opens: {opens}, Closes: {closes}")
