# Third-Party Notices

ICARUS bundles the following third-party libraries into its packaged extension
jar (`icarus-extension/build_manual/libs/icarus-*.jar`), unmodified. Versions
are the exact artifacts downloaded by `icarus-extension/build.sh` /
`icarus-extension/build.ps1`.

`net.portswigger.burp.extensions:montoya-api` is **compile-only** — Burp Suite
provides it at runtime — and is therefore *not* bundled or listed below.

License identifiers below are summarised from the project sources; the
authoritative license text ships in each project's own repository.

| Library | Version | License | Project |
| ------- | ------- | ------- | ------- |
| OpenPDF (`com.github.librepdf:openpdf`) | 2.0.2 | MPL-2.0 OR LGPL-2.1-or-later | https://github.com/LibrePDF/OpenPDF |
| commonmark-java (`org.commonmark:commonmark`) | 0.30.0 | BSD-2-Clause | https://github.com/commonmark/commonmark-java |
| commonmark-java GFM tables (`org.commonmark:commonmark-ext-gfm-tables`) | 0.30.0 | BSD-2-Clause | https://github.com/commonmark/commonmark-java |
| Apache Commons CSV (`org.apache.commons:commons-csv`) | 1.10.0 | Apache-2.0 | https://commons.apache.org/proper/commons-csv/ |
| FlatLaf (`com.formdev:flatlaf`) | 3.4.1 | Apache-2.0 | https://github.com/JFormDesigner/FlatLaf |
| FlatLaf Extras (`com.formdev:flatlaf-extras`) | 3.4.1 | Apache-2.0 | https://github.com/JFormDesigner/FlatLaf |
| JSVG (`com.github.weisj:jsvg`) | 1.4.0 | Apache-2.0 | https://github.com/weisJ/jsvg |
| RSyntaxTextArea (`com.fifesoft:rsyntaxtextarea`) | 3.3.3 | BSD-3-Clause | https://github.com/bobbylight/RSyntaxTextArea |
| jsoup (`org.jsoup:jsoup`) | 1.17.2 | MIT | https://jsoup.org/ |
| MCP Java SDK core (`io.modelcontextprotocol.sdk:mcp-core`) | 1.1.3 | Apache-2.0 | https://github.com/modelcontextprotocol/java-sdk |
| MCP Java SDK Jackson2 JSON (`io.modelcontextprotocol.sdk:mcp-json-jackson2`) | 1.1.3 | Apache-2.0 | https://github.com/modelcontextprotocol/java-sdk |
| Jackson Databind (`com.fasterxml.jackson.core:jackson-databind`) | 2.20.1 | Apache-2.0 | https://github.com/FasterXML/jackson-databind |
| Jackson Core (`com.fasterxml.jackson.core:jackson-core`) | 2.20.1 | Apache-2.0 | https://github.com/FasterXML/jackson-core |
| Jackson Annotations (`com.fasterxml.jackson.core:jackson-annotations`) | 2.20 | Apache-2.0 | https://github.com/FasterXML/jackson-annotations |
| Project Reactor Core (`io.projectreactor:reactor-core`) | 3.7.0 | Apache-2.0 | https://github.com/reactor/reactor-core |
| Reactive Streams (`org.reactivestreams:reactive-streams`) | 1.0.4 | MIT-0 | https://github.com/reactive-streams/reactive-streams-jvm |
| SLF4J API (`org.slf4j:slf4j-api`) | 2.0.16 | MIT | https://www.slf4j.org/ |

## Notes

### OpenPDF

Used for PDF report export (`icarus.evidence.PdfReportGenerator`). Dual-licensed
under the Mozilla Public License 2.0 or the GNU Lesser General Public License
2.1 or later (`SPDX: MPL-2.0 OR LGPL-2.1-or-later`). ICARUS uses OpenPDF
unmodified; its own source is unaffected.

- https://www.mozilla.org/en-US/MPL/2.0/
- https://www.gnu.org/licenses/old-licenses/lgpl-2.1

OpenPDF's transitive `icu4j` dependency (RTL/complex-script text layout) is
deliberately **not** bundled — ICARUS's reports are plain left-to-right English
text, and the `Document`/`Paragraph`/`PdfPTable`/`Image` APIs used here work
fully without it. Omitting it saves roughly 15 MB in the shipped jar.

### MCP Java SDK

`mcp-json-jackson2` is bundled for its `McpJsonMapper`; the SDK's servlet
transport and `com.networknt:json-schema-validator` are intentionally excluded
(see `icarus.mcp` package documentation). Jackson (`databind`/`core`/
`annotations`) and Project Reactor (`reactor-core` + `reactive-streams`) are
the MCP SDK's required runtime dependencies. `slf4j-api` is a no-op API jar with
no binding bundled.

### Apache-2.0 attribution

Per Apache License 2.0 §4, the above table constitutes the required attribution
notice for the Apache-2.0-licensed components. No NOTICE-file content from those
projects is reproduced here because none impose additional notice text beyond
standard attribution.
