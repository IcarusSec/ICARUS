# Graph Report - BurpCustomActions  (2026-08-22)

## Corpus Check
- Large corpus: 95 files · ~548,778 words. Semantic extraction will be expensive (many Claude tokens). Consider running on a subfolder.

## Summary
- 1076 nodes · 3026 edges · 79 communities (51 shown, 28 thin omitted)
- Extraction: 83% EXTRACTED · 17% INFERRED · 0% AMBIGUOUS · INFERRED: 526 edges (avg confidence: 0.81)
- Token cost: 0 input · 0 output

## Community Hubs (Navigation)
- Montoya Ui Userinterface
- Montoya Core Range
- Api Montoya Montoyaapi
- Document
- Icarus Evidence Evidencecolors
- Calltoolresult
- Factory
- Core Finding Builder
- Evidencepaths Evidencepaths De
- Auditissueseverity
- Montoya Collaborator Collabora
- Finding Builder Build
- Http Message Httprequestrespon
- Finding Finding Category
- Frame
- Editor Extension Editorcreatio
- Utilities Json Jsonnode
- Architecture Core Concepts
- Evidence Evidenceannotator Ann
- Evidencepaths Evidencepaths Ev
- Moduleconfig Moduleconfig Getb
- Core Category Category
- Http Handler Httpresponserecei
- Icarus Evidence Evidenceuihelp
- Api Montoya Burpextension
- Changelog
- Message Responses Httpresponse
- Finding Finding Builder
- Changelog Evidence Manager
- Docs Getting Started
- Icarus Extension Evidencecaptu
- Icarus Extension Project
- Evidence Cwerepository Cwe
- Core Concepts Autoauthmodule
- Docs Index
- Access Control Bypass
- Tests Validator Boundary Testi
- Verboseerrordetector Verboseer
- Postmanexportmodule Java Overr
- Readme
- Checker Alg None
- Extension Docs Generated
- Modules Rate Limit
- Docs Workflows
- Github Workflows Security
- Evidence Manager Openpdf
- Docs Generated Category
- Paramval Burp Repeater
- Graphify Graph Json
- Httverb Checkout Request
- Update Icarus Logo
- Mcp
- Graphify Get Node
- Graphify Graphify Path
- Graphify Graphify Query
- Issue Template Config
- Sh Entry
- Docs Generated Cwerepository
- Docs Generated Pdfreportgenera
- Graphify Graph Report
- Graphify Graphify Update
- Graphify Wiki Index
- Template Bug Report
- Template Detection Quality
- Template Feature Request
- Docs Generated Autoauthpreview
- Docs Generated Has
- Docs Generated Icarustab
- Docs Generated Jsonparser
- Docs Generated Jsonpaths
- Docs Generated Rawnumber
- Docs Generated Settingspanel
- Docs Generated To
- Docs Generated Toastnotificati
- Com Icarus Icarus
- Security

## God Nodes (most connected - your core abstractions)
1. `Finding` - 123 edges
2. `ModuleConfig` - 105 edges
3. `Orchestrator` - 62 edges
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
- `Evidence Manager & Reporting feature doc` --conceptually_related_to--> `Evidence Manager Redesign (Master-Detail UI)`  [INFERRED]
  docs/features/evidence_manager.md → CHANGELOG.md

## Import Cycles
- None detected.

## Hyperedges (group relationships)
- **IcarusModule, ModuleConfig, Finding, FindingRegistry, and ScanRunner together form the core scan execution contract/flow** — docs_architecture_core_concepts_icarusmodule, docs_architecture_core_concepts_moduleconfig, docs_architecture_core_concepts_finding, docs_architecture_core_concepts_findingregistry, docs_architecture_core_concepts_scanrunner [INFERRED 0.85]
- **Evidence Manager, CWE tagging, and OpenPDF-based export form the end-to-end reporting pipeline described across changelog, README, and docs** — changelog_evidence_manager, readme_evidence_manager, docs_features_evidence_manager, third_party_notices_openpdf [INFERRED 0.80]
- **build.sh referenced across CI workflows, PR checklist, README, and getting-started docs as the single compile entrypoint** — github_workflows_build_build_sh, github_workflows_security_build_sh, readme_build_sh, docs_getting_started_build_sh, github_pull_request_template_build_sh [INFERRED 0.85]
- **RateLimitModule three-phase attack flow** — docs_modules_rate_limit_ratelimitmodule, docs_modules_rate_limit_burst_detection, docs_modules_rate_limit_characterization, docs_modules_rate_limit_bypass_attempts [EXTRACTED 1.00]
- **ICARUS testing workflow lifecycle** — docs_workflows, docs_workflows_context_menu_integration, docs_workflows_managing_active_scans, docs_workflows_reviewing_findings [EXTRACTED 1.00]
- **Modules implementing the IcarusModule contract** — icarus_extension_docs_generated_icarusmodule, icarus_extension_docs_generated_httpverbmodule, icarus_extension_docs_generated_jwtcheckermodule, icarus_extension_docs_generated_paramvalidatormodule, icarus_extension_docs_generated_ratelimitmodule, icarus_extension_docs_generated_sensitiveheadermodule [INFERRED 0.80]
- **Graphify CLI Commands** — _agents_rules_graphify_graphify_query, _agents_rules_graphify_graphify_path, _agents_rules_graphify_graphify_explain, _agents_rules_graphify_graphify_update [INFERRED 0.95]
- **Graphify MCP Tools** — _agents_rules_graphify_query_graph, _agents_rules_graphify_shortest_path, _agents_rules_graphify_get_node [INFERRED 0.95]

## Communities (79 total, 28 thin omitted)

### Community 0 - "Montoya Ui Userinterface"
Cohesion: 0.06
Nodes (29): burp.api.montoya.ui.UserInterface, DefaultListModel, DefaultTableModel, CapturedEvidence, EvidenceManagerTab, Component, JPanel, JTextField (+21 more)

### Community 1 - "Montoya Core Range"
Cohesion: 0.05
Nodes (21): burp.api.montoya.core.Range, burp.api.montoya.http.handler.HttpRequestToBeSent, burp.api.montoya.http.message.requests.HttpRequest, burp.api.montoya.ui.contextmenu.ContextMenuEvent, burp.api.montoya.ui.contextmenu.MessageEditorHttpRequestResponse, AutoAuthSelfCheck, Range, AutoAuthModule (+13 more)

### Community 2 - "Api Montoya Montoyaapi"
Cohesion: 0.06
Nodes (20): burp.api.montoya.MontoyaApi, burp.api.montoya.ui.contextmenu.ContextMenuItemsProvider, FunctionalInterface, HttpHandler, FindingRecord, FindingRegistry, EvidenceCapture, EvidencePhase2Dialog (+12 more)

### Community 3 - "Document"
Cohesion: 0.08
Nodes (12): Document, SuppressWarnings, ReportTemplateConfig, Section, ReportGenerator, org.commonmark.parser.Parser, org.commonmark.renderer.html.HtmlRenderer, org.openpdf.text.pdf.PdfPCell (+4 more)

### Community 4 - "Icarus Evidence Evidencecolors"
Cohesion: 0.09
Nodes (25): EvidenceColorScheme, EvidenceImageRenderer, BufferedImage, Color, Font, Graphics2D, Override, MarkdownPdfRenderer (+17 more)

### Community 5 - "Calltoolresult"
Cohesion: 0.15
Nodes (4): CallToolResult, IcarusMcpServer, RecheckResult, SyncToolSpecification

### Community 6 - "Factory"
Cohesion: 0.10
Nodes (17): Factory, HttpClient, IcarusJsonSchemaValidator, Override, IcarusMcpTransportProvider, HttpResponse, Override, io.modelcontextprotocol.json.McpJsonMapper (+9 more)

### Community 7 - "Core Finding Builder"
Cohesion: 0.10
Nodes (8): Builder, SuppressWarnings, Json, Parser, ImportedItem, ImportResult, SuppressWarnings, ProjectStateCodec

### Community 8 - "Evidencepaths Evidencepaths De"
Cohesion: 0.12
Nodes (8): BlastResult, Override, RateLimitModule, SingleResult, Component, JButton, Component, JButton

### Community 9 - "Auditissueseverity"
Cohesion: 0.11
Nodes (13): AuditIssueSeverity, EvidencePaths, Severity, CRITICAL, FIXED, HIGH, INFO, LOW (+5 more)

### Community 10 - "Montoya Collaborator Collabora"
Cohesion: 0.16
Nodes (9): burp.api.montoya.collaborator.CollaboratorClient, burp.api.montoya.collaborator.CollaboratorPayload, Override, Mutation, MutationResult, MutationSpec, ParamValidatorModule, PathRules (+1 more)

### Community 12 - "Http Message Httprequestrespon"
Cohesion: 0.20
Nodes (3): burp.api.montoya.http.message.HttpRequestResponse, IcarusModule, ScanRunner

### Community 13 - "Finding Finding Category"
Cohesion: 0.27
Nodes (3): BufferedImage, Rectangle, RateLimitTableRenderer

### Community 14 - "Frame"
Cohesion: 0.15
Nodes (12): Frame, EvidenceAnnotator, BufferedImage, Color, Graphics2D, Color, Rectangle, ToastNotification (+4 more)

### Community 15 - "Editor Extension Editorcreatio"
Cohesion: 0.16
Nodes (10): burp.api.montoya.ui.editor.extension.EditorCreationContext, burp.api.montoya.ui.editor.extension.ExtensionProvidedHttpRequestEditor, burp.api.montoya.ui.editor.extension.HttpRequestEditorProvider, burp.api.montoya.ui.Selection, AutoAuthPreviewEditorProvider, JScrollPane, JTextArea, Override (+2 more)

### Community 16 - "Utilities Json Jsonnode"
Cohesion: 0.22
Nodes (4): burp.api.montoya.utilities.json.JsonNode, Override, JwtCandidate, JwtCheckerModule

### Community 17 - "Architecture Core Concepts"
Cohesion: 0.12
Nodes (19): Core Concepts & Execution doc, Finding class, FindingRegistry, IcarusModule interface, IcarusTab (UI, Results table), ModuleConfig class, Observer Pattern (FindingRegistry notifies UI), ScanRunner (+11 more)

### Community 18 - "Evidence Evidenceannotator Ann"
Cohesion: 0.21
Nodes (6): Annotation, EvidenceAutoRenderer, BufferedImage, Rectangle, Image, java.awt.Rectangle

### Community 19 - "Evidencepaths Evidencepaths Ev"
Cohesion: 0.15
Nodes (4): BufferedImage, Color, java.awt.image.BufferedImage, JFrame

### Community 20 - "Moduleconfig Moduleconfig Getb"
Cohesion: 0.23
Nodes (5): HttpVerbModule, Override, VerbResult, HttpResponseReceived, ResponseReceivedAction

### Community 21 - "Core Category Category"
Cohesion: 0.12
Nodes (16): Category, ACCESS_CONTROL, BOUNDARY, EXPORT, HEADER_LEAK, HEADER_MISSING, HTTP_METHOD, INFORMATION_DISCLOSURE (+8 more)

### Community 22 - "Http Handler Httpresponserecei"
Cohesion: 0.23
Nodes (6): burp.api.montoya.http.handler.HttpResponseReceived, Override, RawNumber, VerboseErrorDetector, io.modelcontextprotocol.server.McpSyncServer, java.util.regex.Pattern

### Community 23 - "Icarus Evidence Evidenceuihelp"
Cohesion: 0.20
Nodes (6): EvidenceUiHelpers, Component, JButton, JPanel, JScrollPane, JTextArea

### Community 24 - "Api Montoya Burpextension"
Cohesion: 0.23
Nodes (4): burp.api.montoya.BurpExtension, ModuleConfig, Icarus, Override

### Community 25 - "Changelog"
Cohesion: 0.22
Nodes (13): ICARUS Changelog, IcarusModule contract (live logger), ScanRunner (live logging wiring), v1.1.4 The First Ascension (initial unified UI), v1.1.5 The First Ascension: Flap of Wings, v1.1.5a The First Ascension: Even Bugs Fly, v1.1.5b Release, v1.1.6 Daedalus (series opener) (+5 more)

### Community 26 - "Message Responses Httpresponse"
Cohesion: 0.29
Nodes (3): burp.api.montoya.http.message.responses.HttpResponse, Override, SensitiveHeaderModule

### Community 27 - "Finding Finding Builder"
Cohesion: 0.31
Nodes (3): EvidenceTriggerService, BufferedImage, Image

### Community 28 - "Changelog Evidence Manager"
Cohesion: 0.22
Nodes (9): Evidence Manager Redesign (Master-Detail UI), Embedded MCP Server (ServerSocket, AI integration), ParamValidator module (v1.4.0 URL/GET query testing), v1.4.0 Release (Evidence Manager redesign, MCP server), Evidence Manager & Reporting feature doc, Offline CWE Tagging, Drag-and-Drop Reordering, One-Click Apply (+1 more)

### Community 29 - "Docs Getting Started"
Cohesion: 0.22
Nodes (9): Getting Started guide, build.sh (getting started compile step), Pull Request Template, build.sh (PR checklist requirement), Conventional Commits format, Build GitHub Actions Workflow, build.sh (CI build step), build.sh (install/compile script) (+1 more)

### Community 30 - "Icarus Extension Evidencecaptu"
Cohesion: 0.22
Nodes (9): EvidenceCapture (ICARUS module), Burp AI Button, Burp Repeater UI, Error Disclosure Tab, Evidence Capture Screenshot (Error Disclosure), NumberFormatException / Apache Struts 2.3.31 stack trace disclosure vulnerability, Request Pane (GET /product?productId=teste), Response Pane (500 NumberFormatException stack trace) (+1 more)

### Community 31 - "Icarus Extension Project"
Cohesion: 0.31
Nodes (9): ICARUS Burp Suite Extension, Icarus Greek Mythology Reference, ICARUS v1.2 "Hecate Update", Icarus Silhouette (Winged Falling Figure), ICARUS Banner Logo, Hecate (six-armed goddess illustration), ICARUS Logo (Hecate Update), Icarus Winged-Figure Silhouette (+1 more)

### Community 32 - "Evidence Cwerepository Cwe"
Cohesion: 0.36
Nodes (3): Cwe, CweRepository, SuppressWarnings

### Community 33 - "Core Concepts Autoauthmodule"
Cohesion: 0.25
Nodes (8): AutoAuthModule (interceptor), Montoya HttpHandler, AutoAuth Engine feature doc, Host-Scoped constraint prevents cross-host token leakage, Persistent Storage of source/destination mappings, Silent Background Refresh, Source-Destination Mapping Model, AutoAuth (AutoAuthModule)

### Community 34 - "Docs Index"
Cohesion: 0.25
Nodes (8): ICARUS Documentation Index, HTTP Verb Tester Module Doc, JWT & Bearer Token Checker Module Doc, JSON Parameter Validator Module Doc, Passive Scanners Doc, SensitiveHeaderModule (concept), Toast Notification (passive finding alert), SensitiveHeaderModule

### Community 35 - "Access Control Bypass"
Cohesion: 0.25
Nodes (8): Access Control Bypass Detection (verb tampering), Automated Mutation (HTTP method tampering), Body Adjustments on Verb Mutation, HttpVerbModule (concept), OPTIONS & Allow Headers Inspection, TRACE Reflection (XST Detection), Privilege Escalation Tampering (JWT claims), HttpVerbModule

### Community 36 - "Tests Validator Boundary Testi"
Cohesion: 0.25
Nodes (8): Boundary Testing (overflow, empty strings), Deep Injection (SQLi/NoSQLi/XSS/Path Traversal in JSON), ParamValidatorModule (concept), Structural Fuzzing (missing fields, empty structures, null injection), Type Confusion Fuzzing, PassiveErrorModule (concept), ParamValidatorModule, PassiveErrorModule

### Community 39 - "Readme"
Cohesion: 0.25
Nodes (8): ICARUS README, HTTP Verb Tester (HttpVerbModule), JWT / Bearer Token Checker (JwtCheckerModule), ParamValidator (JSON Input Validation), Passive Error Detector (PassiveErrorModule), Export to Postman (PostmanExportModule), Rate Limit Tester (RateLimitModule), Sensitive Header Scanner (SensitiveHeaderModule)

### Community 40 - "Checker Alg None"
Cohesion: 0.29
Nodes (7): Algorithm Manipulation (alg=none), Automated JWT Discovery, Embedded Claim Abuse (jku/jwk/kid), JwtCheckerModule (concept), Signature Stripping, Time-based Bypass Detection (exp/iat), JwtCheckerModule

### Community 41 - "Extension Docs Generated"
Cohesion: 0.29
Nodes (7): ICARUS Technical Documentation (generated), AutoAuthModule, Icarus (extension entry point), IcarusModule (interface contract), ModuleConfig, Orchestrator, PostmanExportModule

### Community 42 - "Modules Rate Limit"
Cohesion: 0.33
Nodes (6): Rate Limit Tester Module Doc, Phase 1: Burst Detection, Phase 3: Bypass Attempts (IP spoofing headers), Phase 2: Characterization, RateLimitModule (concept), RateLimitModule

### Community 43 - "Docs Workflows"
Cohesion: 0.47
Nodes (6): Core Testing Workflows Doc, Context Menu Integration, Managing Active Scans (Active Tasks view), Reviewing Findings (Results tab), EvidenceCapture, EvidenceColorScheme

### Community 44 - "Github Workflows Security"
Cohesion: 0.33
Nodes (6): Security Checks GitHub Actions Workflow, build.sh (security workflow build step), CodeQL Analysis, Dependency Review Action, Gitleaks Secret Scanning, Semgrep (p/java ruleset)

### Community 45 - "Evidence Manager Openpdf"
Cohesion: 0.40
Nodes (5): OpenPDF (PDF export), Third-Party Notices, Omit icu4j transitive dep: saves ~15MB jar size vs ~2MB benefit, reports are plain LTR English, OpenPDF library, icarus.evidence.PdfReportGenerator

### Community 46 - "Docs Generated Category"
Cohesion: 0.40
Nodes (5): Category, Finding, FindingRecord, FindingRegistry, Severity

### Community 47 - "Paramval Burp Repeater"
Cohesion: 0.50
Nodes (5): Burp Suite Repeater Tab, POST /api/checkout Request (JSON body with chosen_discount/chosen_products), 201 Created Response (Location: /cart?err=INSUFFICIENT_FUNDS), item_price parameter (133700), ParamValidator Repeater UI Demo (GIF)

### Community 48 - "Graphify Graph Json"
Cohesion: 0.50
Nodes (4): graphify-out/graph.json, Graphify Knowledge Graph, Graphify Skill, Graphify Workflow

### Community 49 - "Httverb Checkout Request"
Cohesion: 0.67
Nodes (4): POST /api/checkout Request, 201 Created Checkout Response, HTTPVerbTester Repeater Tab, HTTPVerbTester Repeater Screenshot

### Community 50 - "Update Icarus Logo"
Cohesion: 0.83
Nodes (4): ICARUS Logo (Mnemosyne circle emblem), ICARUS Project (Burp Suite Extension), Mnemosyne Figure Illustration, ICARUS v1.3 "Mnemosyne Update" Release

## Ambiguous Edges - Review These
- `VerboseErrorDetector` → `CweRepository`  [AMBIGUOUS]
  icarus-extension/docs_generated.md · relation: conceptually_related_to
- `Icarus (extension entry point)` → `Orchestrator`  [AMBIGUOUS]
  icarus-extension/docs_generated.md · relation: conceptually_related_to
- `201 Created Response (Location: /cart?err=INSUFFICIENT_FUNDS)` → `item_price parameter (133700)`  [AMBIGUOUS]
  .images/paramval.gif · relation: tampering_yields_insufficient_funds_error

## Knowledge Gaps
- **122 isolated node(s):** `/usr/bin/python3`, `build.sh script`, `com.icarus:icarus`, `HEADER`, `BODY` (+117 more)
  These have ≤1 connection - possible missing edges or undocumented components.
- **28 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **What is the exact relationship between `VerboseErrorDetector` and `CweRepository`?**
  _Edge tagged AMBIGUOUS (relation: conceptually_related_to) - confidence is low._
- **What is the exact relationship between `Icarus (extension entry point)` and `Orchestrator`?**
  _Edge tagged AMBIGUOUS (relation: conceptually_related_to) - confidence is low._
- **What is the exact relationship between `201 Created Response (Location: /cart?err=INSUFFICIENT_FUNDS)` and `item_price parameter (133700)`?**
  _Edge tagged AMBIGUOUS (relation: tampering_yields_insufficient_funds_error) - confidence is low._
- **Why does `Finding` connect `Finding Builder Build` to `Montoya Ui Userinterface`, `Api Montoya Montoyaapi`, `Document`, `Calltoolresult`, `Core Finding Builder`, `Evidencepaths Evidencepaths De`, `Auditissueseverity`, `Montoya Collaborator Collabora`, `Http Message Httprequestrespon`, `Finding Finding Category`, `Utilities Json Jsonnode`, `Evidence Evidenceannotator Ann`, `Evidencepaths Evidencepaths Ev`, `Moduleconfig Moduleconfig Getb`, `Core Category Category`, `Http Handler Httpresponserecei`, `Api Montoya Burpextension`, `Message Responses Httpresponse`, `Finding Finding Builder`, `Verboseerrordetector Verboseer`, `Postmanexportmodule Java Overr`?**
  _High betweenness centrality (0.154) - this node is a cross-community bridge._
- **Why does `ModuleConfig` connect `Api Montoya Burpextension` to `Montoya Ui Userinterface`, `Montoya Core Range`, `Api Montoya Montoyaapi`, `Document`, `Icarus Evidence Evidencecolors`, `Calltoolresult`, `Core Finding Builder`, `Evidencepaths Evidencepaths De`, `Auditissueseverity`, `Montoya Collaborator Collabora`, `Finding Builder Build`, `Http Message Httprequestrespon`, `Finding Finding Category`, `Utilities Json Jsonnode`, `Evidence Evidenceannotator Ann`, `Evidencepaths Evidencepaths Ev`, `Moduleconfig Moduleconfig Getb`, `Http Handler Httpresponserecei`, `Message Responses Httpresponse`, `Finding Finding Builder`, `Verboseerrordetector Verboseer`, `Postmanexportmodule Java Overr`?**
  _High betweenness centrality (0.139) - this node is a cross-community bridge._
- **Why does `AutoAuthModule` connect `Montoya Core Range` to `Montoya Ui Userinterface`, `Api Montoya Montoyaapi`, `Auditissueseverity`, `Editor Extension Editorcreatio`, `Api Montoya Burpextension`, `Finding Finding Builder`?**
  _High betweenness centrality (0.063) - this node is a cross-community bridge._
- **What connects `/usr/bin/python3`, `build.sh script`, `com.icarus:icarus` to the rest of the system?**
  _122 weakly-connected nodes found - possible documentation gaps or missing edges._