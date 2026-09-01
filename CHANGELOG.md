# Changelog

All notable changes to the ICARUS Burp Suite extension are documented here.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project aims to follow [Semantic Versioning](https://semver.org/).
Full release notes (with complete detail) live on the
[GitHub Releases page](https://github.com/IcarusSec/ICARUS/releases).

## [Unreleased]

_Nothing yet._

## [1.5.0] - 2026-08-31

Report Profiles architecture, an AST-based mutation engine, behavioral WAF
detection and evasion, a much larger MCP tool surface, a Windows build, and a
long run of stability fixes.

### Added

- **Report Profiles:** multi-model `ReportProfile` architecture with dynamic
  per-profile settings, drag-and-drop section flow, responsive layout panels,
  and a master-detail Markdown editor for custom report sections.
- **Reports:** GFM pipe tables in custom sections (HTML report), the `FINDINGS`
  section can be placed anywhere and is a non-removable default, per-line
  annotation anchors, and HIGH→LOW finding ordering.
- **WAF evasion:** behavioral WAF detection with a mid-scan evasion-payload
  jump, multi-vendor fingerprinting, a non-destructive Safe Mode, DEEP evasion,
  OWASP CRS 4-tuned payload lists for XSS/NoSQLi/path/SQLi, and GLOB- and
  NOTNULL-based SQLi bypasses that defeat `libinjection`.
- **ParamValidator:** scans form-urlencoded bodies and URL/GET query
  parameters, supports a non-2xx baseline with a baseline-stability gate,
  status-transition detection (behind a flag), and SSRF / IDOR / CMDi / SSTI
  detection.
- **MCP:** Streamable HTTP transport plus a much wider tool set —
  `generate_report`, evidence capture / annotation / crop tools,
  evidence-manager and report-config tools, `get_finding_traffic`, exposed
  request/response traffic, `rescan_finding` to rebind stale findings,
  `validate_finding` / `exploit_finding` with attack-chain correlation, a
  "Wipe Registry" tool, and code-block evidence. `ARROW` annotations honour
  named anchors.
- **Retest Mode:** a Retest checkbox in the Evidence Editor that stamps
  `FIXED` / `NOT FIXED` banners (green/red) on evidence images.
- **Evidence:** MCP can render evidence images without a screenshot; the ICARUS
  banner is the default report logo.
- **Build:** `build.ps1` — a Windows PowerShell build using Burp's bundled JDK.
- **UI:** Catppuccin, Dracula, Nord, Gruvbox, Burp Proxy Night, Burp Carbon and
  Burp Midnight evidence colour schemes; bundled Tabler icons.
- **Diagnostics:** a debug-logging mode for the finding-heavy freeze/crash, with
  a pinned debug-log folder.
- **i18n:** English is the default language and the UI/knowledge base are fully
  translated; the vulnerability knowledge base was rebuilt to match the tool's
  real finding types.

### Changed

- UI accent colour switched from blue to ICARUS/Burp orange (`#FF6633`).
- Reports always render an image and only include confirmed findings.
- Evidence screenshots always land in an `icarus-evidence/` subfolder.
- MCP server advertises server-level instructions grounded in what is actually
  implemented, and logs its registered tool list on start.
- FlatLaf core is bundled into the jar (not just `flatlaf-extras`).
- Version pinned to `1.5.0` in `Icarus.java`.

### Fixed

- Findings are matched by similarity hash, not object identity, so
  rescanned/edited findings are no longer dropped from reports.
- PDF generation closes its `FileOutputStream` when it fails before
  `doc.open()` (Windows file-lock leak).
- ParamValidator reuses the parsed JSON tree across mutations instead of
  re-parsing per mutation, and guards the WAF-throttle `invokeAndWait` against
  an EDT caller.
- Project import stages and validates every evidence image off the EDT before
  clearing the Evidence Manager.
- `Finding.builder` sets the path, preventing cross-endpoint hash collisions.
- Null-safety fixes for MCP manual coordinates, the AST `parseNumber` infinite
  loop, and the knowledge-base resource stream.
- Thread-safe collections for registry listeners and captured evidence.
- Evidence image height is capped so a huge request/response body can't
  OOM-crash Burp; RateLimit request count and thread concurrency are capped;
  finding-update UI fan-outs are coalesced so a rate-limit blast can't freeze
  Burp.
- `PatternSyntaxException` in the findings-review table filter is handled.
- Many Reporting-tab layout, scroll and `ClassCastException` fixes.
- Flat-param mutation values are percent-encoded.
- Target binding is persisted in both the finding registry state and evidence;
  service-less findings are rebuilt from the `Host` header.
- Auto-rendered evidence no longer bakes in a guessed default annotation box.

### Removed

- `REDACT` and full-pane `HIGHLIGHT` fill annotations.
- The binary-payload dialog — request/response bodies always render verbatim.
- MCP legacy SSE transport and its machine-derived auth key (replaced by
  Streamable HTTP).
- The superseded `bambda-scripts` directory and unused knowledge-base CSVs.

### Security

- `exploit_finding` is gated behind a blocking human-approval dialog; WAF Safe
  Mode keeps evasion non-destructive by default.
- `SERVER_ERROR`, `VERBOSE_ERROR_LEAK` and `VERSION_DISCLOSURE`, and
  boolean-based SQLi (`STRING_SQLI`), get real validate/exploit rechecks
  instead of a trusted stored result.

## [1.4.0] - 2026-08-18

This major release completely rebuilds the Evidence Manager, ships an embedded Model Context Protocol (MCP) server for AI integration, and vastly upgrades the extension's Evidence Capture UI and HTML/PDF Reporting features.

**Highlights**
- **Complete Evidence Manager Redesign**: Rebuilt the Evidence Manager into a Master-Detail UI with drag-and-drop support, allowing you to seamlessly organize findings and paste screenshots directly from your clipboard.
- **Embedded AI Integration (MCP)**: Shipped an embedded Model Context Protocol (MCP) server so local AI agents can interact with ICARUS findings and automate your workflows.
- **Enhanced Evidence Capture UX**: Upgraded the evidence capture workflow with automatic compact mode, multi-monitor support for pop-out windows, canvas zooming, and collapsible toolbars. All pop-up dialogs have been upgraded to support native OS maximization.
- **Advanced HTML & PDF Reporting**: Reports now fully support Markdown rendering, multiple evidence images per finding, dynamic themes (light/dark modes), table of contents, and a new "Retest Mode".

**User Interface & Experience**
- **Evidence Capture Overhaul:** Implemented Phase 1-3 UX changes including canvas zoom, smart dialog bounds, scrollable toolbars, and collapsible panels. Added Advanced Layouts featuring an automatic Compact Mode based on screen width detection. Added support for detaching/popping out the evidence editor, with complete multi-monitor boundary support. Upgraded pop-ups to support maximization.
- **Evidence Manager Redesign:** Rebuilt the entire tab as a Master-Detail UI. Added double-click editing, drag-and-drop finding reorganization, and support to paste browser screenshots directly from the clipboard.
- **General UX & Navigation:** Refactored the Results tab bottom bar and added keyboard shortcuts. Reordered the right-click context menu and removed the top-level ICARUS menu to reduce clutter. Extracted configurations into dedicated tabs. Added an "Export/Import .icarus Project State" feature.

**Reporting & Documentation**
- **Rich Report Generation:** Bundled `commonmark-java` to render rich Markdown inside reports. HTML and PDF engines now fully support rendering multiple evidence images per finding, markdown sections, and bookmarks/TOC. Added base themes (light/dark mode) and color presets for the HTML report generator. Introduced **Retest Mode** to flag and track vulnerability remediations.
- **Rate Limit Capture Improvements:** Stripped query parameters from endpoint paths for deduplication. Removed redundant URLs in descriptions. Routed Rate Limit captures through Phase 1 to allow title/description editing. Added dynamic status coloring (Red/Yellow/Green) to the RPS metric header in the evidence screenshot.
- **Settings & Configs:** Added a dedicated Report Template configuration tab. You can now explicitly configure the evidence output directory. Added a "Reset to Default" configuration button.

**AI / Model Context Protocol (MCP)**
- Embedded a local MCP server over a raw `ServerSocket` to allow external LLM agents to query ICARUS securely.
- Added an MCP settings tab to easily change the binding port (default `61337`), securely deriving its API key from the local machine's identity.

**Core Logic & Bug Fixes**
- Extended `ParamValidator` module to aggressively test URL/GET query parameters.
- Refactored the internal module loader to optionally disable certain modules at build time.
- Grouped evidence screenshots by a similarity hash rather than object identity.
- Fixed JSON parser silently swallowing non-JSON HTTP bodies as empty strings.
- Fixed caption edits in the Evidence Manager truncating to the first character.
- Fixed HotKey registration failures blocking the extension boot sequence.
- Resolved minor bugs regarding right-click context tracking, auto-updating, and export file paths.

## [1.3.0] - 2026-08-06 — Mnemosyne

A full reporting overhaul, named for the Titaness of Memory: ICARUS now remembers, curates, and formats your evidence instead of just capturing it. Evidence Capture gets a one-click Apply that auto-renders a screenshot and registers the finding straight into the report — fixing a real bug where captured screenshots never actually attached to a generated report once you edited anything — plus offline CWE tagging with typeahead, and a Proxy History import for building evidence without running a scan. New Evidence Manager window (right-click → "Send to Reporter Creation", or the Results tab) previews and edits captured screenshots, drag-and-drop reorders them, and lets you include/exclude a finding from the next report without deleting its evidence. Reports gain a Preview-in-browser step before export, an optional executive summary section, and — new — PDF export via OpenPDF alongside HTML, both sharing one consistent light theme. Also fixes stray passive findings (header checks, error disclosures) silently landing in generated reports despite never being sent to Evidence Capture, and a PDF page-break bug that separated finding descriptions from their own screenshots.

## [1.2.0] - 2026-07-31 — Hecate

AutoAuth: highlight-and-click token capture/injection that replaces Burp Macros — silent background refresh, host-scoped injection targets, persists across restarts. New Passive Error Detector flags HTTP 500+ and verbose error leaks in the background; Smart Evidence Capture pre-fills evidence from detected issues; toast notifications for background findings. Evidence capture QOL: a real working Ctrl+P hotkey (Command Palette-visible), smarter binary payload handling (Hex Dump/Keep Original/Truncate), Copy to Clipboard, more annotation shortcuts. Rate Limit Tester gets a real global Max RPS throttle and audit log export. Also fixes a ParamValidator false-positive on 4xx responses and a Spacebar-hold-to-pan focus-stealing bug, and bumps the vendored montoya-api dependency to 2026.7.

## [1.1.7] - 2026-07-30 — Daedalus: Argos Panoptes — Cloak & Dagger

WAF evasion and false-positive elimination for ParamValidator: 401/403 WAF block pages no longer trigger false-positive injection findings, with an EDT-safe throttle prompt on repeated blocks. Intelligent finding synthesis (worst-first rollup per parameter), injection severity tiering, and distinct injection tagging by mutation type. Broadened Akamai CDN detection and expanded SQLite error detection across PHP/Python/raw error codes.

## [1.1.6b] - 2026-07-29 — Daedalus: Argos Panoptes

Architectural simplification pass, CWE-200/CWE-209 sensitive-data-leak detection, deep rate-limiting engine improvements, and SQLMap-style real-time verbose logging across all modules.

## [1.1.6a] - 2026-07-29 — Daedalus: Message from Hermes

Live Logging Architecture: the `IcarusModule` contract now carries a live logger into every module's `run()`, wired through `ScanRunner` to both the Live Log popup and Burp's Output tab. JWT Checker and ParamValidator narrate their active tests step-by-step as they fire. New Verbose Mode toggle (Settings → General, on by default).

## [1.1.6] - 2026-07-29 — Daedalus

Series opener for the Daedalus line of releases.

## [1.1.5b] - 2026-07-28

Flameshot-style hotkeys in the evidence image editor, dynamic RPS metrics for rate-limit testing, and substantial UI/rendering fixes for evidence screenshots.

## [1.1.5a] - 2026-07-28 — The First Ascension: Even Bugs Fly

Evidence-capture bug fixes.

## [1.1.5] - 2026-07-27 — The First Ascension: Flap of Wings

Fixed a config-persistence bug that could corrupt suppression rules/custom payload lists after restart. JWT Checker now confirms before sending active tampering requests, with an optional claim-redaction toggle. Fixed Swing threading issues in the scan progress UI. "Run All Modules" no longer fires Postman Export automatically; Postman Export now actually saves to a chosen file with overwrite confirmation; HTML report generation prompts for a save location instead of silently reusing the evidence folder.

## [1.1.4] - 2026-07-27 — The First Ascension

Initial unified Burp Suite interface for ICARUS modules: smart evidence capture with full payload logging and manual evidence popups, the Rate Limit Tester, and automated scanning from the extension UI.

[Unreleased]: https://github.com/IcarusSec/ICARUS/compare/v1.5.0...HEAD
[1.5.0]: https://github.com/IcarusSec/ICARUS/compare/v1.4.0...v1.5.0
[1.4.0]: https://github.com/IcarusSec/ICARUS/compare/v1.3...v1.4.0
[1.3.0]: https://github.com/IcarusSec/ICARUS/compare/v1.2...v1.3
[1.2.0]: https://github.com/IcarusSec/ICARUS/compare/v1.1.7...v1.2
[1.1.7]: https://github.com/IcarusSec/ICARUS/compare/v1.1.6b...v1.1.7
[1.1.6b]: https://github.com/IcarusSec/ICARUS/compare/v1.1.6a...v1.1.6b
[1.1.6a]: https://github.com/IcarusSec/ICARUS/compare/v1.1.6...v1.1.6a
[1.1.6]: https://github.com/IcarusSec/ICARUS/compare/v1.1.5b...v1.1.6
[1.1.5b]: https://github.com/IcarusSec/ICARUS/releases/tag/v1.1.5b
[1.1.5a]: https://github.com/IcarusSec/ICARUS/releases
[1.1.5]: https://github.com/IcarusSec/ICARUS/releases
[1.1.4]: https://github.com/IcarusSec/ICARUS/releases
