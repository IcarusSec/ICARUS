# Evidence Manager & Reporting

A massive portion of security testing is documentation. ICARUS's Evidence Manager is designed to turn raw HTTP traffic into a client-ready deliverable with near-zero friction.

## Capturing Evidence

You can capture evidence from anywhere in Burp (Proxy, Logger, Repeater).

1. Right-click the request/response you want to capture.
2. Select **Extensions > ICARUS > Send to Reporter Creation** (or hit `Ctrl+P`).
3. **One-Click Apply:** In the popup, click "Apply". ICARUS automatically renders a screenshot of the HTTP exchange, calculates severity based on common signatures, and saves it into the active report state. No file-save dialogs are necessary.

## The Evidence Manager Interface

Navigate to the **ICARUS Tab > Evidence Manager** to curate your captured findings.

- **Drag-and-Drop Reordering:** Drag rows to reorder how findings will appear in the final report.
- **Offline CWE Tagging:** Click the CWE column on a finding. A typeahead search will query the bundled, offline CWE dataset. Simply type "SQL" or "Cross Site" and select the appropriate CWE taxonomy tag.
- **Toggling Visibility:** You can mark findings to be excluded from the *next* report export without permanently deleting their underlying evidence images.
- **Annotations:** If a finding needs markup, right-click it and open the annotation editor to add boxes, highlights, redactions, or arrows directly to the HTTP payload screenshot.

## Exporting Reports

ICARUS generates stunning, self-contained reports entirely offline.
1. Click **Preview Report** to render the HTML report directly within an embedded browser in Burp Suite.
2. If satisfied, click **Export to PDF**. ICARUS utilizes OpenPDF to render a highly polished, professional PDF deliverable containing all tagged CWEs, evidence screenshots, and executive summaries.
