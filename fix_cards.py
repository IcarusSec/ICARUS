import sys

with open('icarus-extension/src/main/java/icarus/ui/ReportingSettingsTab.java.bak', 'r') as f:
    bak_content = f.read()

cardpanel_start = bak_content.find("    /** Reusable card panel matching SettingsPanel's style. */")
cardpanel_end = bak_content.find("    private JButton btn(String text, String icon, java.awt.event.ActionListener al) {")
cardpanel_code = bak_content[cardpanel_start:cardpanel_end]

wrapper_method = """
    private ResponsiveSection wrapInCard(String title, String iconName, ResponsiveSection inner) {
        CardPanel card = new CardPanel(title, iconName);
        card.addFormRow(inner.component());
        return new ResponsiveSection() {
            @Override
            public Component component() {
                return card;
            }
            @Override
            public void onBreakpointChanged(Breakpoint bp) {
                inner.onBreakpointChanged(bp);
            }
        };
    }
"""

with open('icarus-extension/src/main/java/icarus/ui/ReportingSettingsTab.java', 'r') as f:
    content = f.read()

# Insert the CardPanel class and wrapper method before the btn method
btn_pos = content.find("    private JButton btn(String text, String icon, java.awt.event.ActionListener al) {")
content = content[:btn_pos] + cardpanel_code + wrapper_method + "\n" + content[btn_pos:]

# Update the registerSection calls in buildContent
content = content.replace(
    "responsiveContainer.registerSection(toolbarPanel);",
    "responsiveContainer.registerSection(wrapInCard(\"Report Profile & Actions\", \"file-text\", toolbarPanel));"
)
content = content.replace(
    "responsiveContainer.registerSection(layoutPanel);",
    "responsiveContainer.registerSection(wrapInCard(\"Layout\", \"square\", layoutPanel));"
)
content = content.replace(
    "responsiveContainer.registerSection(flowPanel);",
    "responsiveContainer.registerSection(wrapInCard(\"Sections Flow\", \"list\", flowPanel));"
)
content = content.replace(
    "responsiveContainer.registerSection(themePanel);",
    "responsiveContainer.registerSection(wrapInCard(\"Colors & Theme\", \"aperture\", themePanel));"
)
content = content.replace(
    "responsiveContainer.registerSection(brandingPanel);",
    "responsiveContainer.registerSection(wrapInCard(\"Branding & Metadata\", \"shield\", brandingPanel));"
)
content = content.replace(
    "responsiveContainer.registerSection(contentPanel);",
    "responsiveContainer.registerSection(wrapInCard(\"Content & Policy\", \"adjustments-horizontal\", contentPanel));"
)

with open('icarus-extension/src/main/java/icarus/ui/ReportingSettingsTab.java', 'w') as f:
    f.write(content)
