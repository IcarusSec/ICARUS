# Changelog

All notable changes to the ICARUS Burp Suite extension are documented here. Full release notes (with complete detail) live on the [GitHub Releases page](https://github.com/IcarusSec/IcarusBambdaScripts/releases).

## [v1.3] — Mnemosyne — 2026-08-06

A full reporting overhaul, named for the Titaness of Memory: ICARUS now remembers, curates, and formats your evidence instead of just capturing it. Evidence Capture gets a one-click Apply that auto-renders a screenshot and registers the finding straight into the report — fixing a real bug where captured screenshots never actually attached to a generated report once you edited anything — plus offline CWE tagging with typeahead, and a Proxy History import for building evidence without running a scan. New Evidence Manager window (right-click → "Send to Reporter Creation", or the Results tab) previews and edits captured screenshots, drag-and-drop reorders them, and lets you include/exclude a finding from the next report without deleting its evidence. Reports gain a Preview-in-browser step before export, an optional executive summary section, and — new — PDF export via OpenPDF alongside HTML, both sharing one consistent light theme. Also fixes stray passive findings (header checks, error disclosures) silently landing in generated reports despite never being sent to Evidence Capture, and a PDF page-break bug that separated finding descriptions from their own screenshots.

## [v1.2] — Hecate — 2026-07-31

AutoAuth: highlight-and-click token capture/injection that replaces Burp Macros — silent background refresh, host-scoped injection targets, persists across restarts. New Passive Error Detector flags HTTP 500+ and verbose error leaks in the background; Smart Evidence Capture pre-fills evidence from detected issues; toast notifications for background findings. Evidence capture QOL: a real working Ctrl+P hotkey (Command Palette-visible), smarter binary payload handling (Hex Dump/Keep Original/Truncate), Copy to Clipboard, more annotation shortcuts. Rate Limit Tester gets a real global Max RPS throttle and audit log export. Also fixes a ParamValidator false-positive on 4xx responses and a Spacebar-hold-to-pan focus-stealing bug, and bumps the vendored montoya-api dependency to 2026.7.

## [v1.1.7] — Daedalus: Argos Panoptes — Cloak & Dagger — 2026-07-30

WAF evasion and false-positive elimination for ParamValidator: 401/403 WAF block pages no longer trigger false-positive injection findings, with an EDT-safe throttle prompt on repeated blocks. Intelligent finding synthesis (worst-first rollup per parameter), injection severity tiering, and distinct injection tagging by mutation type. Broadened Akamai CDN detection and expanded SQLite error detection across PHP/Python/raw error codes.

## [v1.1.6b] — Daedalus: Argos Panoptes — 2026-07-29

Architectural simplification pass, CWE-200/CWE-209 sensitive-data-leak detection, deep rate-limiting engine improvements, and SQLMap-style real-time verbose logging across all modules.

## [v1.1.6a] — Daedalus: Message from Hermes — 2026-07-29

Live Logging Architecture: the `IcarusModule` contract now carries a live logger into every module's `run()`, wired through `ScanRunner` to both the Live Log popup and Burp's Output tab. JWT Checker and ParamValidator narrate their active tests step-by-step as they fire. New Verbose Mode toggle (Settings → General, on by default).

## [v1.1.6] — Daedalus — 2026-07-29

Series opener for the Daedalus line of releases.

## [v1.1.5b] — 2026-07-28

Flameshot-style hotkeys in the evidence image editor, dynamic RPS metrics for rate-limit testing, and substantial UI/rendering fixes for evidence screenshots.

## [v1.1.5a] — The First Ascension: Even Bugs Fly — 2026-07-28

Evidence-capture bug fixes.

## [v1.1.5] — The First Ascension: Flap of Wings — 2026-07-27

Fixed a config-persistence bug that could corrupt suppression rules/custom payload lists after restart. JWT Checker now confirms before sending active tampering requests, with an optional claim-redaction toggle. Fixed Swing threading issues in the scan progress UI. "Run All Modules" no longer fires Postman Export automatically; Postman Export now actually saves to a chosen file with overwrite confirmation; HTML report generation prompts for a save location instead of silently reusing the evidence folder.

## [v1.1.4] — The First Ascension — 2026-07-27

Initial unified Burp Suite interface for ICARUS modules: smart evidence capture with full payload logging and manual evidence popups, the Rate Limit Tester, and automated scanning from the extension UI.
