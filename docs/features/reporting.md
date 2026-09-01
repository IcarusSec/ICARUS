# 📄 Dynamic Report Engine

ICARUS 1.0 features a completely reimagined reporting engine. Instead of a rigid, hardcoded output, you now have total control over what your report looks like, how it's structured, and what content it includes.

## 🗂 Report Profiles
ICARUS uses a **Report Profiles** architecture. This means you aren't stuck constantly changing settings for different clients. 
You can create, save, and seamlessly swap between different configurations (e.g., "Internal Dev Team", "Client A Executive", "Client B Detailed"). Every change you make in the Reporting Settings tab is automatically saved to the active profile.

## 🧩 Sections Flow UI
The Sections Flow UI is where you structure your report's document flow. 
- **Built-in Sections:** Sections like `FINDINGS` (the actual vulnerability list and evidence) are built-in.
- **Custom Sections:** You can add custom sections (e.g., "Executive Summary", "Methodology", "Scope").
- **Drag and Drop:** Grab any section and drag it up or down to reorder exactly how the final report will be generated.
- **Rename & Toggle:** You can double-click to rename sections and use the toggle switch to temporarily hide a section without deleting it.

## ✍️ Master-Detail Markdown Editor
ICARUS natively supports Markdown for all custom sections. 
When you select a custom section in the Flow UI, a rich Markdown editor appears on the right. 
- The HTML and PDF generators will natively parse this markdown (including lists, bold/italics, and code blocks) into the final report. 

## 🎨 Themes and Branding
ICARUS reports shouldn't look like generic automated dumps. 
- **Logo File Pickers:** Easily attach your company logo or the client's logo to the report.
- **Built-in Themes:** Out of the box, ICARUS includes gorgeous modern themes including *Catppuccin, Dracula, Nord, Gruvbox*, and *Burp Proxy Night*, as well as a clean *Light* theme for traditional PDFs.
- **Auto-Cloning Themes:** Want to tweak a built-in theme? Just edit any color. ICARUS will automatically clone the read-only built-in theme into a custom, editable copy so you can adjust primary/secondary accents without losing the original.

## 🔄 Retest Mode
If you are performing a retest, simply toggle the **Retest Mode** in the Evidence Manager.
Findings marked as "Fixed" will have their evidence images automatically stamped with a bright green `FIXED` banner, and "Not Fixed" will be stamped with a red `NOT FIXED` banner. This visual indicator seamlessly flows into both the HTML and PDF reports.

## 📤 Export Formats
Reports can be exported in two formats directly from the Evidence Manager:
1. **Interactive HTML:** A single-file, highly responsive HTML document containing embedded Base64 images and interactive tables. Great for sharing via email or Slack.
2. **Professional PDF:** A strictly formatted, paginated PDF generated natively offline via OpenPDF. It features a Table of Contents, page-break-safe finding cards, and vector-crisp text.
