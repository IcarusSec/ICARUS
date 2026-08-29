# Graph Report - BurpCustomActions  (2026-08-23)

## Corpus Check
- 83 files · ~548,843 words
- Verdict: corpus is large enough that graph structure adds value.

## Summary
- 1081 nodes · 3032 edges · 75 communities (46 shown, 29 thin omitted)
- Extraction: 83% EXTRACTED · 17% INFERRED · 0% AMBIGUOUS · INFERRED: 527 edges (avg confidence: 0.81)
- Token cost: 0 input · 0 output

## Graph Freshness
- Built from commit: `5670fe64`
- Run `git rev-parse HEAD` and compare to check if the graph is stale.
- Run `graphify update .` after code changes (no API cost).

## Community Hubs (Navigation)
- .applyTheme
- AutoAuthModule
- FindingRegistry
- ReportTemplateConfig
- PdfReportGenerator
- IcarusMcpServer
- IcarusMcpTransportProvider
- Parser
- burp.api.montoya.http.message.requests.HttpRequest
- Severity
- .getBool
- CapturedEvidence
- IcarusModule
- TestMontoya
- ToastNotification
- JwtCheckerModule
- FindingRegistry
- Finding
- Category
- EvidenceUiHelpers
- ModuleConfig
- ICARUS Changelog
- SensitiveHeaderModule
- Orchestrator
- Evidence Manager & Reporting feature doc
- build.sh (getting started compile step)
- Burp Repeater UI
- ICARUS Burp Suite Extension
- CweRepository
- AutoAuth Engine feature doc
- ICARUS Documentation Index
- HttpVerbModule (concept)
- ParamValidatorModule (concept)
- .analyze
- PostmanExportModule
- ICARUS README
- JwtCheckerModule (concept)
- IcarusModule (interface contract)
- RateLimitModule (concept)
- Core Testing Workflows Doc
- Security Checks GitHub Actions Workflow
- OpenPDF library
- Finding
- ParamValidator Repeater UI Demo (GIF)
- Graphify Knowledge Graph
- HTTPVerbTester Repeater Tab
- ICARUS v1.3 "Mnemosyne Update" Release
- code-review-graph
- get_node
- graphify path
- graphify query
- Issue Template Config
- build.sh script
- CweRepository
- PdfReportGenerator
- graphify-out/GRAPH_REPORT.md
- graphify update
- graphify-out/wiki/index.md
- Bug Report Issue Template
- Detection Quality (FP/FN) Issue Template
- Feature Request Issue Template
- AutoAuthPreviewEditorProvider
- has
- IcarusTab
- JsonParser
- JsonPaths
- RawNumber
- SettingsPanel
- to
- ToastNotification
- com.icarus:icarus
- Security Policy (SECURITY.md)

## God Nodes (most connected - your core abstractions)
1. `Finding` - 123 edges
2. `ModuleConfig` - 105 edges
3. `Orchestrator` - 63 edges
4. `IcarusMcpServer` - 52 edges
5. `EvidenceCapture` - 49 edges
6. `ReportTemplateConfig` - 47 edges
7. `AutoAuthModule` - 46 edges
8. `Category` - 33 edges
9. `Severity` - 33 edges
10. `CapturedEvidence` - 32 edges

## Surprising Connections (you probably didn't know these)
- `build.sh (getting started compile step)` --semantically_similar_to--> `build.sh (PR checklist requirement)`  [INFERRED] [semantically similar]
  docs/getting_started.md → .github/pull_request_template.md
- `build.sh (getting started compile step)` --semantically_similar_to--> `build.sh (CI build step)`  [INFERRED] [semantically similar]
  docs/getting_started.md → .github/workflows/build.yml
- `build.sh (getting started compile step)` --semantically_similar_to--> `build.sh (install/compile script)`  [INFERRED] [semantically similar]
  docs/getting_started.md → README.md
- `OpenPDF (PDF export)` --semantically_similar_to--> `OpenPDF library`  [INFERRED] [semantically similar]
  docs/features/evidence_manager.md → THIRD-PARTY-NOTICES.md
- `Evidence Manager & Reporting feature doc` --conceptually_related_to--> `Evidence Manager & Reporting`  [INFERRED]
  docs/features/evidence_manager.md → README.md

## Import Cycles
- None detected.

## Hyperedges (group relationships)
- **ICARUS testing workflow lifecycle** — docs_workflows, docs_workflows_context_menu_integration, docs_workflows_managing_active_scans, docs_workflows_reviewing_findings [EXTRACTED 1.00]
- **RateLimitModule three-phase attack flow** — docs_modules_rate_limit_ratelimitmodule, docs_modules_rate_limit_burst_detection, docs_modules_rate_limit_characterization, docs_modules_rate_limit_bypass_attempts [EXTRACTED 1.00]
- **Evidence Manager, CWE tagging, and OpenPDF-based export form the end-to-end reporting pipeline described across changelog, README, and docs** — changelog_evidence_manager, readme_evidence_manager, docs_features_evidence_manager, third_party_notices_openpdf [INFERRED 0.80]
- **Modules implementing the IcarusModule contract** — icarus_extension_docs_generated_icarusmodule, icarus_extension_docs_generated_httpverbmodule, icarus_extension_docs_generated_jwtcheckermodule, icarus_extension_docs_generated_paramvalidatormodule, icarus_extension_docs_generated_ratelimitmodule, icarus_extension_docs_generated_sensitiveheadermodule [INFERRED 0.80]
- **build.sh referenced across CI workflows, PR checklist, README, and getting-started docs as the single compile entrypoint** — github_workflows_build_build_sh, github_workflows_security_build_sh, readme_build_sh, docs_getting_started_build_sh, github_pull_request_template_build_sh [INFERRED 0.85]
- **IcarusModule, ModuleConfig, Finding, FindingRegistry, and ScanRunner together form the core scan execution contract/flow** — docs_architecture_core_concepts_icarusmodule, docs_architecture_core_concepts_moduleconfig, docs_architecture_core_concepts_finding, docs_architecture_core_concepts_findingregistry, docs_architecture_core_concepts_scanrunner [INFERRED 0.85]
- **Graphify CLI Commands** — _agents_rules_graphify_graphify_query, _agents_rules_graphify_graphify_path, _agents_rules_graphify_graphify_explain, _agents_rules_graphify_graphify_update [INFERRED 0.95]
- **Graphify MCP Tools** — _agents_rules_graphify_query_graph, _agents_rules_graphify_shortest_path, _agents_rules_graphify_get_node [INFERRED 0.95]

## Communities (75 total, 29 thin omitted)

### Community 0 - ".applyTheme"
Cohesion: 0.07
Nodes (25): burp.api.montoya.ui.UserInterface, DefaultListModel, DefaultTableModel, Component, IcarusTab, Component, JPanel, Component (+17 more)

### Community 1 - "AutoAuthModule"
Cohesion: 0.05
Nodes (22): burp.api.montoya.core.Range, burp.api.montoya.http.handler.HttpRequestToBeSent, burp.api.montoya.ui.contextmenu.ContextMenuEvent, burp.api.montoya.ui.contextmenu.MessageEditorHttpRequestResponse, AutoAuthSelfCheck, Range, AutoAuthModule, InjectionTarget (+14 more)

### Community 2 - "FindingRegistry"
Cohesion: 0.09
Nodes (3): AuditIssueSeverity, FindingRecord, FindingRegistry

### Community 3 - "ReportTemplateConfig"
Cohesion: 0.10
Nodes (5): SuppressWarnings, ReportTemplateConfig, Section, ReportGenerator, org.commonmark.renderer.html.HtmlRenderer

### Community 4 - "PdfReportGenerator"
Cohesion: 0.09
Nodes (31): Document, Override, MarkdownPdfRenderer, Font, Image, PdfReportGenerator, java.awt.Color, ListItem (+23 more)

### Community 5 - "IcarusMcpServer"
Cohesion: 0.05
Nodes (22): CallToolResult, EvidenceAutoRenderer, BufferedImage, Rectangle, BufferedImage, EvidenceColorScheme, EvidenceImageRenderer, BufferedImage (+14 more)

### Community 6 - "IcarusMcpTransportProvider"
Cohesion: 0.10
Nodes (17): Factory, HttpClient, IcarusJsonSchemaValidator, Override, IcarusMcpTransportProvider, HttpResponse, Override, io.modelcontextprotocol.json.McpJsonMapper (+9 more)

### Community 7 - "Parser"
Cohesion: 0.21
Nodes (3): SuppressWarnings, Json, Parser

### Community 8 - "burp.api.montoya.http.message.requests.HttpRequest"
Cohesion: 0.10
Nodes (15): burp.api.montoya.http.message.requests.HttpRequest, burp.api.montoya.ui.editor.extension.EditorCreationContext, burp.api.montoya.ui.editor.extension.ExtensionProvidedHttpRequestEditor, burp.api.montoya.ui.editor.extension.HttpRequestEditorProvider, burp.api.montoya.ui.Selection, AutoAuthPreviewEditorProvider, JScrollPane, JTextArea (+7 more)

### Community 9 - "Severity"
Cohesion: 0.17
Nodes (12): burp.api.montoya.BurpExtension, Severity, CRITICAL, FIXED, HIGH, INFO, LOW, MEDIUM (+4 more)

### Community 10 - ".getBool"
Cohesion: 0.10
Nodes (14): burp.api.montoya.collaborator.CollaboratorClient, burp.api.montoya.collaborator.CollaboratorPayload, HttpVerbModule, Override, VerbResult, Override, Mutation, MutationResult (+6 more)

### Community 11 - "CapturedEvidence"
Cohesion: 0.10
Nodes (13): FunctionalInterface, CapturedEvidence, Component, JButton, Component, JButton, Component, JButton (+5 more)

### Community 14 - "ToastNotification"
Cohesion: 0.16
Nodes (13): Frame, Annotation, EvidenceAnnotator, BufferedImage, Color, Graphics2D, Color, Rectangle (+5 more)

### Community 16 - "JwtCheckerModule"
Cohesion: 0.22
Nodes (4): burp.api.montoya.utilities.json.JsonNode, Override, JwtCandidate, JwtCheckerModule

### Community 17 - "FindingRegistry"
Cohesion: 0.12
Nodes (19): Core Concepts & Execution doc, Finding class, FindingRegistry, IcarusModule interface, IcarusTab (UI, Results table), ModuleConfig class, Observer Pattern (FindingRegistry notifies UI), ScanRunner (+11 more)

### Community 19 - "Finding"
Cohesion: 0.13
Nodes (5): Finding, Override, EvidencePhase2Dialog, java.awt.datatransfer.DataFlavor, java.awt.image.BufferedImage

### Community 21 - "Category"
Cohesion: 0.08
Nodes (21): Category, ACCESS_CONTROL, BOUNDARY, EXPORT, HEADER_LEAK, HEADER_MISSING, HTTP_METHOD, INFORMATION_DISCLOSURE (+13 more)

### Community 23 - "EvidenceUiHelpers"
Cohesion: 0.17
Nodes (7): EvidenceUiHelpers, Color, Component, JButton, JPanel, JScrollPane, JTextArea

### Community 24 - "ModuleConfig"
Cohesion: 0.13
Nodes (7): burp.api.montoya.MontoyaApi, EvidencePaths, ModuleConfig, EvidenceCapture, EvidencePhase1Dialog, Override, ProjectStateService

### Community 25 - "ICARUS Changelog"
Cohesion: 0.22
Nodes (13): ICARUS Changelog, IcarusModule contract (live logger), ScanRunner (live logging wiring), v1.1.4 The First Ascension (initial unified UI), v1.1.5 The First Ascension: Flap of Wings, v1.1.5a The First Ascension: Even Bugs Fly, v1.1.5b Release, v1.1.6 Daedalus (series opener) (+5 more)

### Community 26 - "SensitiveHeaderModule"
Cohesion: 0.25
Nodes (4): burp.api.montoya.http.handler.HttpResponseReceived, burp.api.montoya.http.message.responses.HttpResponse, Override, SensitiveHeaderModule

### Community 27 - "Orchestrator"
Cohesion: 0.12
Nodes (8): burp.api.montoya.http.message.HttpRequestResponse, burp.api.montoya.ui.contextmenu.ContextMenuItemsProvider, HttpHandler, EvidenceTriggerService, BufferedImage, Image, FindingsReviewDialog, Orchestrator

### Community 28 - "Evidence Manager & Reporting feature doc"
Cohesion: 0.22
Nodes (9): Evidence Manager Redesign (Master-Detail UI), Embedded MCP Server (ServerSocket, AI integration), ParamValidator module (v1.4.0 URL/GET query testing), v1.4.0 Release (Evidence Manager redesign, MCP server), Evidence Manager & Reporting feature doc, Offline CWE Tagging, Drag-and-Drop Reordering, One-Click Apply (+1 more)

### Community 29 - "build.sh (getting started compile step)"
Cohesion: 0.22
Nodes (9): Getting Started guide, build.sh (getting started compile step), Pull Request Template, build.sh (PR checklist requirement), Conventional Commits format, Build GitHub Actions Workflow, build.sh (CI build step), build.sh (install/compile script) (+1 more)

### Community 30 - "Burp Repeater UI"
Cohesion: 0.22
Nodes (9): EvidenceCapture (ICARUS module), Burp AI Button, Burp Repeater UI, Error Disclosure Tab, Evidence Capture Screenshot (Error Disclosure), NumberFormatException / Apache Struts 2.3.31 stack trace disclosure vulnerability, Request Pane (GET /product?productId=teste), Response Pane (500 NumberFormatException stack trace) (+1 more)

### Community 31 - "ICARUS Burp Suite Extension"
Cohesion: 0.31
Nodes (9): ICARUS Burp Suite Extension, Icarus Greek Mythology Reference, ICARUS v1.2 "Hecate Update", Icarus Silhouette (Winged Falling Figure), ICARUS Banner Logo, Hecate (six-armed goddess illustration), ICARUS Logo (Hecate Update), Icarus Winged-Figure Silhouette (+1 more)

### Community 32 - "CweRepository"
Cohesion: 0.31
Nodes (3): Cwe, CweRepository, SuppressWarnings

### Community 33 - "AutoAuth Engine feature doc"
Cohesion: 0.25
Nodes (8): AutoAuthModule (interceptor), Montoya HttpHandler, AutoAuth Engine feature doc, Host-Scoped constraint prevents cross-host token leakage, Persistent Storage of source/destination mappings, Silent Background Refresh, Source-Destination Mapping Model, AutoAuth (AutoAuthModule)

### Community 34 - "ICARUS Documentation Index"
Cohesion: 0.25
Nodes (8): ICARUS Documentation Index, HTTP Verb Tester Module Doc, JWT & Bearer Token Checker Module Doc, JSON Parameter Validator Module Doc, Passive Scanners Doc, SensitiveHeaderModule (concept), Toast Notification (passive finding alert), SensitiveHeaderModule

### Community 35 - "HttpVerbModule (concept)"
Cohesion: 0.25
Nodes (8): Access Control Bypass Detection (verb tampering), Automated Mutation (HTTP method tampering), Body Adjustments on Verb Mutation, HttpVerbModule (concept), OPTIONS & Allow Headers Inspection, TRACE Reflection (XST Detection), Privilege Escalation Tampering (JWT claims), HttpVerbModule

### Community 36 - "ParamValidatorModule (concept)"
Cohesion: 0.25
Nodes (8): Boundary Testing (overflow, empty strings), Deep Injection (SQLi/NoSQLi/XSS/Path Traversal in JSON), ParamValidatorModule (concept), Structural Fuzzing (missing fields, empty structures, null injection), Type Confusion Fuzzing, PassiveErrorModule (concept), ParamValidatorModule, PassiveErrorModule

### Community 39 - "ICARUS README"
Cohesion: 0.25
Nodes (8): ICARUS README, HTTP Verb Tester (HttpVerbModule), JWT / Bearer Token Checker (JwtCheckerModule), ParamValidator (JSON Input Validation), Passive Error Detector (PassiveErrorModule), Export to Postman (PostmanExportModule), Rate Limit Tester (RateLimitModule), Sensitive Header Scanner (SensitiveHeaderModule)

### Community 40 - "JwtCheckerModule (concept)"
Cohesion: 0.29
Nodes (7): Algorithm Manipulation (alg=none), Automated JWT Discovery, Embedded Claim Abuse (jku/jwk/kid), JwtCheckerModule (concept), Signature Stripping, Time-based Bypass Detection (exp/iat), JwtCheckerModule

### Community 41 - "IcarusModule (interface contract)"
Cohesion: 0.29
Nodes (7): ICARUS Technical Documentation (generated), AutoAuthModule, Icarus (extension entry point), IcarusModule (interface contract), ModuleConfig, Orchestrator, PostmanExportModule

### Community 42 - "RateLimitModule (concept)"
Cohesion: 0.33
Nodes (6): Rate Limit Tester Module Doc, Phase 1: Burst Detection, Phase 3: Bypass Attempts (IP spoofing headers), Phase 2: Characterization, RateLimitModule (concept), RateLimitModule

### Community 43 - "Core Testing Workflows Doc"
Cohesion: 0.47
Nodes (6): Core Testing Workflows Doc, Context Menu Integration, Managing Active Scans (Active Tasks view), Reviewing Findings (Results tab), EvidenceCapture, EvidenceColorScheme

### Community 44 - "Security Checks GitHub Actions Workflow"
Cohesion: 0.33
Nodes (6): Security Checks GitHub Actions Workflow, build.sh (security workflow build step), CodeQL Analysis, Dependency Review Action, Gitleaks Secret Scanning, Semgrep (p/java ruleset)

### Community 45 - "OpenPDF library"
Cohesion: 0.40
Nodes (5): OpenPDF (PDF export), Third-Party Notices, Omit icu4j transitive dep: saves ~15MB jar size vs ~2MB benefit, reports are plain LTR English, OpenPDF library, icarus.evidence.PdfReportGenerator

### Community 46 - "Finding"
Cohesion: 0.40
Nodes (5): Category, Finding, FindingRecord, FindingRegistry, Severity

### Community 47 - "ParamValidator Repeater UI Demo (GIF)"
Cohesion: 0.50
Nodes (5): Burp Suite Repeater Tab, POST /api/checkout Request (JSON body with chosen_discount/chosen_products), 201 Created Response (Location: /cart?err=INSUFFICIENT_FUNDS), item_price parameter (133700), ParamValidator Repeater UI Demo (GIF)

### Community 48 - "Graphify Knowledge Graph"
Cohesion: 0.50
Nodes (4): graphify-out/graph.json, Graphify Knowledge Graph, Graphify Skill, Graphify Workflow

### Community 49 - "HTTPVerbTester Repeater Tab"
Cohesion: 0.67
Nodes (4): POST /api/checkout Request, 201 Created Checkout Response, HTTPVerbTester Repeater Tab, HTTPVerbTester Repeater Screenshot

### Community 50 - "ICARUS v1.3 "Mnemosyne Update" Release"
Cohesion: 0.83
Nodes (4): ICARUS Logo (Mnemosyne circle emblem), ICARUS Project (Burp Suite Extension), Mnemosyne Figure Illustration, ICARUS v1.3 "Mnemosyne Update" Release

## Ambiguous Edges - Review These
- `Icarus (extension entry point)` → `Orchestrator`  [AMBIGUOUS]
  icarus-extension/docs_generated.md · relation: conceptually_related_to
- `item_price parameter (133700)` → `201 Created Response (Location: /cart?err=INSUFFICIENT_FUNDS)`  [AMBIGUOUS]
  .images/paramval.gif · relation: tampering_yields_insufficient_funds_error
- `CweRepository` → `VerboseErrorDetector`  [AMBIGUOUS]
  icarus-extension/docs_generated.md · relation: conceptually_related_to

## Knowledge Gaps
- **122 isolated node(s):** `/usr/bin/python3`, `build.sh script`, `com.icarus:icarus`, `HEADER`, `BODY` (+117 more)
  These have ≤1 connection - possible missing edges or undocumented components.
- **29 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **What is the exact relationship between `Icarus (extension entry point)` and `Orchestrator`?**
  _Edge tagged AMBIGUOUS (relation: conceptually_related_to) - confidence is low._
- **What is the exact relationship between `item_price parameter (133700)` and `201 Created Response (Location: /cart?err=INSUFFICIENT_FUNDS)`?**
  _Edge tagged AMBIGUOUS (relation: tampering_yields_insufficient_funds_error) - confidence is low._
- **What is the exact relationship between `CweRepository` and `VerboseErrorDetector`?**
  _Edge tagged AMBIGUOUS (relation: conceptually_related_to) - confidence is low._
- **Why does `Finding` connect `Finding` to `.applyTheme`, `FindingRegistry`, `ReportTemplateConfig`, `PdfReportGenerator`, `IcarusMcpServer`, `.analyze`, `PostmanExportModule`, `burp.api.montoya.http.message.requests.HttpRequest`, `Severity`, `.getBool`, `CapturedEvidence`, `IcarusModule`, `JwtCheckerModule`, `Category`, `ModuleConfig`, `SensitiveHeaderModule`, `Orchestrator`?**
  _High betweenness centrality (0.136) - this node is a cross-community bridge._
- **Why does `ModuleConfig` connect `ModuleConfig` to `.applyTheme`, `AutoAuthModule`, `FindingRegistry`, `ReportTemplateConfig`, `PdfReportGenerator`, `IcarusMcpServer`, `.analyze`, `PostmanExportModule`, `burp.api.montoya.http.message.requests.HttpRequest`, `Severity`, `.getBool`, `CapturedEvidence`, `IcarusModule`, `JwtCheckerModule`, `Finding`, `SensitiveHeaderModule`, `Orchestrator`?**
  _High betweenness centrality (0.130) - this node is a cross-community bridge._
- **Why does `IcarusMcpServer` connect `IcarusMcpServer` to `.applyTheme`, `ReportTemplateConfig`, `IcarusMcpTransportProvider`, `Severity`, `Finding`, `ModuleConfig`, `Orchestrator`?**
  _High betweenness centrality (0.071) - this node is a cross-community bridge._
- **What connects `/usr/bin/python3`, `build.sh script`, `com.icarus:icarus` to the rest of the system?**
  _122 weakly-connected nodes found - possible documentation gaps or missing edges._