# Third-Party Notices

ICARUS bundles the following third-party library into its packaged extension jar
(`icarus-extension/build_manual/libs/icarus-*.jar`), unmodified.

## OpenPDF

- **Used for:** PDF report export (`icarus.evidence.PdfReportGenerator`).
- **Project:** https://github.com/LibrePDF/OpenPDF
- **Version:** 3.0.5
- **License:** Dual-licensed — Mozilla Public License 2.0, or GNU Lesser General Public
  License 2.1 or later (`SPDX: MPL-2.0 OR LGPL-2.1+`). ICARUS uses OpenPDF under these terms
  unmodified; its own source is unaffected. See:
  - https://www.mozilla.org/en-US/MPL/2.0/
  - https://www.gnu.org/licenses/old-licenses/lgpl-2.1

Its transitive `icu4j` dependency (used by OpenPDF for RTL/complex-script text layout) is
deliberately **not** bundled — ICARUS's reports are plain left-to-right English text, and
OpenPDF's `Document`/`Paragraph`/`PdfPTable`/`Image` APIs used here work fully without it.
Omitting it saves roughly 15MB in the shipped jar for a library actually adding ~2MB.
