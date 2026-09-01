# Graph Report - BurpCustomActions  (2026-09-01)

## Corpus Check
- 184 files · ~718,038 words
- Verdict: corpus is large enough that graph structure adds value.

## Summary
- 2115 nodes · 5373 edges · 143 communities (104 shown, 33 thin omitted)
- Extraction: 87% EXTRACTED · 13% INFERRED · 0% AMBIGUOUS · INFERRED: 714 edges (avg confidence: 0.81)
- Token cost: 0 input · 0 output

## Graph Freshness
- Built from commit: `c989acfc`
- Run `git rev-parse HEAD` and compare to check if the graph is stale.
- Run `graphify update .` after code changes (no API cost).

## Community Hubs (Navigation)
- AutoAuthModule
- IcarusMcpServer
- burp.api.montoya.http.message.requests.HttpRequest
- PdfReportGenerator
- IcarusModule (interface contract)
- IcarusMcpTransportProvider
- EvidenceCapture
- Finding
- ReportTemplateConfig
- com.fasterxml.jackson.annotation.JsonIgnoreProperties
- CrewAI Task Design Guide
- ReportingSettingsTab
- ReportProfileManager
- ScanRunner
- .buildUI
- FindingRegistry
- Breakpoint
- PostmanExportModule
- .run
- MarkdownPdfRenderer.java
- OffensiveAstRoot
- ModuleConfig
- ICARUS Changelog
- Severity
- WafVendor
- DetailPane
- WireframeKind
- EvidenceImageRenderer
- Builder
- ThemeColors
- AstProperty
- EvidenceTriggerService.java
- ThemeHelper
- ContentSectionPanel
- Orchestrator
- I18n
- CrewAI Getting Started & Architecture
- ProjectContextDetector
- ReportGenerator
- CoverRendererId
- FindingRegistry
- SectionListPanel
- .EvidenceManagerTab
- .getBool
- Category
- JwtCheckerModule (concept)
- ReportProfile
- MCP Servers Reference
- KnowledgeBaseEntry
- CrewAI Tools Catalog
- ResponsiveContainer
- AstNode
- SensitiveHeaderModule
- .current
- SectionListCellRenderer
- .buildUI
- ReportExportService
- Evidence Manager & Reporting feature doc
- EvidencePhase1Dialog
- Custom Tools Reference
- Text
- PassiveErrorModule
- FindingField
- Flow Routing Reference
- Dynamic Report Engine (docs)
- ToastNotification
- 7. Flows — The Production Foundation
- ICARUS Development Guide
- .render
- RateLimitModule (concept)
- AstLeaf
- Transition
- .renderPdf
- .renderPdf
- GradientHeroCoverRenderer
- ICARUS Documentation Index
- .buildReportData
- .addCweChip
- ColorsThemeSectionPanel
- OffensiveJsonParser
- HeaderBandCoverRenderer
- Ask CrewAI Docs
- Memory & Knowledge Reference
- AutoAuth Engine feature doc
- burp.api.montoya.http.message.HttpRequestResponse
- .getString
- Core Testing Workflows Doc
- Security Checks GitHub Actions Workflow
- Finding
- AstMutationGenerator.java
- Graphify Knowledge Graph
- FooterPageEvent
- .renderFinalImage
- 2. Agent Configuration Reference
- TestMontoya
- ponytail.md
- .addFormRow
- CrewAI Agent Design Guide
- code-review-graph
- Conversational Flows Reference
- BrandingSectionPanel
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
- SectionFlowPanel
- ToolbarPanel
- 3. Embedder Configuration
- 4. Agent.kickoff() — Direct Agent Execution
- 📢 ICARUS 1.0: The Complete Offensive Pipeline (Official Launch)
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
- ContentPolicy
- 2. Knowledge Sources
- com.icarus:icarus
- Planning (Plan-and-Execute Mode)
- 0. How Many Agents Do You Actually Need?
- Contributor Covenant Code of Conduct
- 1. The Role-Goal-Backstory Framework
- 6. Agent Interaction Patterns
- .createSmoothScrollPane

## God Nodes (most connected - your core abstractions)
1. `Finding` - 122 edges
2. `ModuleConfig` - 117 edges
3. `Orchestrator` - 77 edges
4. `EvidenceCapture` - 75 edges
5. `ReportTemplateConfig` - 73 edges
6. `IcarusMcpServer` - 66 edges
7. `Severity` - 56 edges
8. `AutoAuthModule` - 49 edges
9. `PdfReportGenerator` - 47 edges
10. `ReportingSettingsTab` - 44 edges

## Surprising Connections (you probably didn't know these)
- `OpenPDF (PDF export)` --semantically_similar_to--> `OpenPDF library`  [INFERRED] [semantically similar]
  docs/features/evidence_manager.md → THIRD-PARTY-NOTICES.md
- `Dynamic Report Engine (docs)` --semantically_similar_to--> `Dynamic Report Engine`  [INFERRED] [semantically similar]
  docs/features/reporting.md → README.md
- `build.sh (getting started compile step)` --semantically_similar_to--> `build.sh (PR checklist requirement)`  [INFERRED] [semantically similar]
  docs/getting_started.md → .github/pull_request_template.md
- `build.sh (getting started compile step)` --semantically_similar_to--> `build.sh (CI build step)`  [INFERRED] [semantically similar]
  docs/getting_started.md → .github/workflows/build.yml
- `JwtCheckerModule (concept)` --references--> `JwtCheckerModule`  [EXTRACTED]
  docs/modules/jwt_checker.md → icarus-extension/docs_generated.md

## Import Cycles
- None detected.

## Hyperedges (group relationships)
- **ICARUS testing workflow lifecycle** — docs_workflows, docs_workflows_context_menu_integration, docs_workflows_managing_active_scans, docs_workflows_reviewing_findings [EXTRACTED 1.00]
- **RateLimitModule three-phase attack flow** — docs_modules_rate_limit_ratelimitmodule, docs_modules_rate_limit_burst_detection, docs_modules_rate_limit_characterization, docs_modules_rate_limit_bypass_attempts [EXTRACTED 1.00]
- **Evidence Manager, CWE tagging, and OpenPDF-based export form the end-to-end reporting pipeline described across changelog, README, and docs** — changelog_evidence_manager, readme_evidence_manager, docs_features_evidence_manager, third_party_notices_openpdf [INFERRED 0.80]
- **Modules implementing the IcarusModule contract** — icarus_extension_docs_generated_icarusmodule, icarus_extension_docs_generated_httpverbmodule, icarus_extension_docs_generated_jwtcheckermodule, icarus_extension_docs_generated_paramvalidatormodule, icarus_extension_docs_generated_ratelimitmodule, icarus_extension_docs_generated_sensitiveheadermodule [INFERRED 0.80]
- **build.sh referenced across CI workflows, PR checklist, README, and getting-started docs as the single compile entrypoint** — github_workflows_build_build_sh, github_workflows_security_build_sh, docs_getting_started_build_sh, github_pull_request_template_build_sh [INFERRED 0.85]
- **IcarusModule, ModuleConfig, Finding, FindingRegistry, and ScanRunner together form the core scan execution contract/flow** — docs_architecture_core_concepts_icarusmodule, docs_architecture_core_concepts_moduleconfig, docs_architecture_core_concepts_finding, docs_architecture_core_concepts_findingregistry, docs_architecture_core_concepts_scanrunner [INFERRED 0.85]
- **Graphify CLI Commands** — _agents_rules_graphify_graphify_query, _agents_rules_graphify_graphify_path, _agents_rules_graphify_graphify_explain, _agents_rules_graphify_graphify_update [INFERRED 0.95]
- **Graphify MCP Tools** — _agents_rules_graphify_query_graph, _agents_rules_graphify_shortest_path, _agents_rules_graphify_get_node [INFERRED 0.95]

## Communities (143 total, 33 thin omitted)

### Community 0 - "AutoAuthModule"
Cohesion: 0.06
Nodes (19): burp.api.montoya.core.Range, burp.api.montoya.http.handler.HttpRequestToBeSent, AutoAuthSelfCheck, Range, AutoAuthModule, InjectionTarget, Source, TargetKind (+11 more)

### Community 1 - "IcarusMcpServer"
Cohesion: 0.12
Nodes (7): CallToolRequest, CallToolResult, HumanApprovalGate, IcarusMcpServer, RecheckResult, io.modelcontextprotocol.server.McpSyncServer, SyncToolSpecification

### Community 2 - "burp.api.montoya.http.message.requests.HttpRequest"
Cohesion: 0.06
Nodes (21): burp.api.montoya.http.message.requests.HttpRequest, burp.api.montoya.ui.editor.extension.EditorCreationContext, burp.api.montoya.ui.editor.extension.ExtensionProvidedHttpRequestEditor, burp.api.montoya.ui.editor.extension.HttpRequestEditorProvider, burp.api.montoya.ui.Selection, burp.api.montoya.utilities.json.JsonNode, AutoAuthPreviewEditorProvider, JScrollPane (+13 more)

### Community 3 - "PdfReportGenerator"
Cohesion: 0.17
Nodes (12): com.lowagie.text.pdf.PdfPCell, com.lowagie.text.pdf.PdfPTable, com.lowagie.text.pdf.PdfWriter, Document, Font, Override, Paragraph, PdfContentByte (+4 more)

### Community 4 - "IcarusModule (interface contract)"
Cohesion: 0.10
Nodes (21): JSON Parameter Validator Module Doc, Boundary Testing (overflow, empty strings), Deep Injection (SQLi/NoSQLi/XSS/Path Traversal in JSON), ParamValidatorModule (concept), Structural Fuzzing (missing fields, empty structures, null injection), Type Confusion Fuzzing, Passive Scanners Doc, PassiveErrorModule (concept) (+13 more)

### Community 5 - "IcarusMcpTransportProvider"
Cohesion: 0.09
Nodes (18): ConcurrentLinkedDeque, Factory, HttpClient, IcarusJsonSchemaValidator, Override, IcarusMcpTransportProvider, HttpResponse, Override (+10 more)

### Community 6 - "EvidenceCapture"
Cohesion: 0.16
Nodes (6): CapturedEvidence, EvidenceCapture, Color, Font, java.awt.image.BufferedImage, JFrame

### Community 8 - "ReportTemplateConfig"
Cohesion: 0.08
Nodes (5): FindingTemplate, SuppressWarnings, ReportTemplateConfig, Section, ReportTemplateConfigMigrator

### Community 9 - "com.fasterxml.jackson.annotation.JsonIgnoreProperties"
Cohesion: 0.12
Nodes (7): com.fasterxml.jackson.annotation.JsonIgnoreProperties, BrandingConfig, HtmlTheme, PageBox, PdfTheme, SectionGraph, SectionNode

### Community 10 - "CrewAI Task Design Guide"
Cohesion: 0.04
Nodes (47): 1. LLM.call() — Direct Pydantic Return, 2. Agent.kickoff() — LiteAgentOutput Wrapper, 3. Task — output_pydantic / output_json, 4. Crew.kickoff() — CrewOutput, Common Pitfalls, Keep Models Simple, Key Difference, output_json (+39 more)

### Community 11 - "ReportingSettingsTab"
Cohesion: 0.15
Nodes (9): ActionListener, Icon, Dimension, JButton, JComboBox, JPanel, JSpinner, Override (+1 more)

### Community 13 - "ScanRunner"
Cohesion: 0.14
Nodes (4): IcarusModule, ScanRunner, WafDecision, ThreadPoolExecutor

### Community 14 - ".buildUI"
Cohesion: 0.17
Nodes (9): CardPanel, CardPanel, Component, Dimension, JComponent, JLabel, JPanel, Override (+1 more)

### Community 15 - "FindingRegistry"
Cohesion: 0.10
Nodes (4): AuditIssueSeverity, FindingRecord, FindingRegistry, SuppressWarnings

### Community 16 - "Breakpoint"
Cohesion: 0.15
Nodes (13): Breakpoint, COMPACT, NARROW, REGULAR, ULTRAWIDE, forWidth(), ResponsiveSection, Dimension (+5 more)

### Community 18 - ".run"
Cohesion: 0.12
Nodes (10): burp.api.montoya.collaborator.CollaboratorClient, burp.api.montoya.collaborator.CollaboratorPayload, BaselineSample, Override, Mutation, MutationResult, MutationSpec, ParamValidatorModule (+2 more)

### Community 19 - "MarkdownPdfRenderer.java"
Cohesion: 0.15
Nodes (20): com.lowagie.text.Element, com.lowagie.text.Font, com.lowagie.text.Paragraph, Override, Paragraph, PdfPCell, MarkdownPdfRenderer, ListItem (+12 more)

### Community 20 - "OffensiveAstRoot"
Cohesion: 0.18
Nodes (4): AstMutationResult, HppMutator, RawByteBoundaryMutator, OffensiveAstRoot

### Community 21 - "ModuleConfig"
Cohesion: 0.12
Nodes (10): burp.api.montoya.BurpExtension, burp.api.montoya.MontoyaApi, EvidencePaths, ModuleConfig, EvidenceAnnotator, EvidencePhase2Dialog, EvidenceUiHelpers, RateLimitTableRenderer (+2 more)

### Community 22 - "ICARUS Changelog"
Cohesion: 0.22
Nodes (13): ICARUS Changelog, IcarusModule contract (live logger), ScanRunner (live logging wiring), v1.1.4 The First Ascension (initial unified UI), v1.1.5 The First Ascension: Flap of Wings, v1.1.5a The First Ascension: Even Bugs Fly, v1.1.5b Release, v1.1.6 Daedalus (series opener) (+5 more)

### Community 23 - "Severity"
Cohesion: 0.11
Nodes (18): Severity, CRITICAL, FIXED, HIGH, INFO, LOW, MEDIUM, NOT_FIXED (+10 more)

### Community 24 - "WafVendor"
Cohesion: 0.15
Nodes (10): burp.api.montoya.http.message.responses.HttpResponse, Sig, WafFingerprint, WafVendor, AKAMAI, AWS_WAF, CLOUDFLARE, F5_BIGIP_ASM (+2 more)

### Community 25 - "DetailPane"
Cohesion: 0.20
Nodes (7): VariableChipRow, DetailPane, Component, JPanel, JTextField, Override, JTextPane

### Community 26 - "WireframeKind"
Cohesion: 0.14
Nodes (10): WireframeKind, ELEVATED_CARD, GRADIENT_HERO, HEADER_BAND, NONE, TABULAR_GRID, Component, JPanel (+2 more)

### Community 27 - "EvidenceImageRenderer"
Cohesion: 0.12
Nodes (8): BufferedImage, EvidenceColorScheme, EvidenceImageRenderer, BufferedImage, Color, Graphics2D, BufferedImage, Rectangle

### Community 28 - "Builder"
Cohesion: 0.18
Nodes (6): Entry, Builder, ImportedItem, ImportResult, SuppressWarnings, StagedImport

### Community 29 - "ThemeColors"
Cohesion: 0.20
Nodes (4): burp.api.montoya.ui.Theme, FontLoader, Font, ThemeColors

### Community 30 - "AstProperty"
Cohesion: 0.13
Nodes (8): AstObject, Override, AstProperty, Override, AstVisitor, BaseAstVisitor, Override, Override

### Community 31 - "EvidenceTriggerService.java"
Cohesion: 0.25
Nodes (7): burp.api.montoya.ui.contextmenu.ContextMenuEvent, burp.api.montoya.ui.contextmenu.ContextMenuItemsProvider, burp.api.montoya.ui.contextmenu.MessageEditorHttpRequestResponse, ProjectStateCodec, java.awt.datatransfer.DataFlavor, java.awt.datatransfer.Transferable, org.commonmark.parser.Parser

### Community 32 - "ThemeHelper"
Cohesion: 0.21
Nodes (7): burp.api.montoya.ui.UserInterface, Color, JLabel, JPanel, JTable, ThemeHelper, javax.swing.border.Border

### Community 33 - "ContentSectionPanel"
Cohesion: 0.19
Nodes (6): ToggleSwitch, ContentSectionPanel, Component, JPanel, JSpinner, Override

### Community 34 - "Orchestrator"
Cohesion: 0.11
Nodes (11): HttpHandler, FindingsReviewDialog, Component, JButton, Orchestrator, Component, JButton, ProjectStateService (+3 more)

### Community 35 - "I18n"
Cohesion: 0.32
Nodes (3): I18n, java.util.ResourceBundle, javax.swing.table.DefaultTableModel

### Community 36 - "CrewAI Getting Started & Architecture"
Cohesion: 0.13
Nodes (15): 10. Quick Diagnostic Checklist, 1. Choosing the Right Abstraction, 2. LLM.call() — Direct LLM Invocation, 3. Agent.kickoff() — Single Agent Execution, 4. CLI Scaffold Reference, 5. YAML Configuration (agents.yaml & tasks.yaml), 6. Wiring It Together — crew.py, 8. Variable Interpolation with `inputs` (+7 more)

### Community 37 - "ProjectContextDetector"
Cohesion: 0.31
Nodes (6): Confidence, HIGH, LOW, MEDIUM, ProjectContext, ProjectContextDetector

### Community 38 - "ReportGenerator"
Cohesion: 0.19
Nodes (3): Extension, ReportGenerator, org.commonmark.renderer.html.HtmlRenderer

### Community 39 - "CoverRendererId"
Cohesion: 0.08
Nodes (14): com.lowagie.text.Document, CoverRendererId, GRADIENT_HERO, HEADER_BAND, NONE, FindingRendererId, ELEVATED_CARD, TABULAR (+6 more)

### Community 40 - "FindingRegistry"
Cohesion: 0.12
Nodes (19): Core Concepts & Execution doc, Finding class, FindingRegistry, IcarusModule interface, IcarusTab (UI, Results table), ModuleConfig class, Observer Pattern (FindingRegistry notifies UI), ScanRunner (+11 more)

### Community 41 - "SectionListPanel"
Cohesion: 0.17
Nodes (11): Component, DataFlavor, DefaultListModel, JComponent, JList, JScrollPane, Override, SectionListPanel (+3 more)

### Community 42 - ".EvidenceManagerTab"
Cohesion: 0.22
Nodes (8): GridBagConstraints, EvidenceManagerTab, Component, JComponent, JPanel, JTextField, JCheckBox, TransferHandler

### Community 43 - ".getBool"
Cohesion: 0.29
Nodes (3): HttpVerbModule, Override, VerbResult

### Community 44 - "Category"
Cohesion: 0.09
Nodes (18): Category, ACCESS_CONTROL, BOUNDARY, EXPORT, HEADER_LEAK, HEADER_MISSING, HTTP_METHOD, INFORMATION_DISCLOSURE (+10 more)

### Community 45 - "JwtCheckerModule (concept)"
Cohesion: 0.12
Nodes (16): HTTP Verb Tester Module Doc, Access Control Bypass Detection (verb tampering), Automated Mutation (HTTP method tampering), Body Adjustments on Verb Mutation, HttpVerbModule (concept), OPTIONS & Allow Headers Inspection, TRACE Reflection (XST Detection), JWT & Bearer Token Checker Module Doc (+8 more)

### Community 46 - "ReportProfile"
Cohesion: 0.15
Nodes (6): com.fasterxml.jackson.databind.ObjectMapper, DefaultReportProfileManager, Override, Override, ReportProfile, ReportProfileCodec

### Community 47 - "MCP Servers Reference"
Cohesion: 0.14
Nodes (14): Advanced: MCPServerAdapter (Manual Lifecycle), Attaching MCP Servers to Agents, Automatic Behaviors, Decision Checklist, Installation, Known Official MCP Servers, MCP Servers Reference, Mixing MCP Servers with Native Tools (+6 more)

### Community 48 - "KnowledgeBaseEntry"
Cohesion: 0.19
Nodes (3): KnowledgeBaseEntry, VulnerabilityKnowledgeBase, java.util.logging.Logger

### Community 49 - "CrewAI Tools Catalog"
Cohesion: 0.14
Nodes (14): AI & Code, Automation & Integration, Cloud & AWS, Common Tool Patterns, CrewAI Tools Catalog, Database, File & Document, File Reading + Writing (+6 more)

### Community 50 - "ResponsiveContainer"
Cohesion: 0.19
Nodes (6): Component, Dimension, Override, Rectangle, ResponsiveContainer, Scrollable

### Community 51 - "AstNode"
Cohesion: 0.14
Nodes (6): AstArray, Override, Override, AstNode, Override, TypeConfusionMutator

### Community 52 - "SensitiveHeaderModule"
Cohesion: 0.16
Nodes (6): burp.api.montoya.http.handler.HttpResponseReceived, Override, SensitiveHeaderModule, HttpResponseReceived, java.util.concurrent.ThreadPoolExecutor, ResponseReceivedAction

### Community 53 - ".current"
Cohesion: 0.14
Nodes (9): Graphics, Override, Graphics, Override, VariableChip, Graphics, Override, WireframePreview (+1 more)

### Community 54 - "SectionListCellRenderer"
Cohesion: 0.12
Nodes (11): Graphics, Override, ThumbnailCard, SectionLabelFormatter, Component, Graphics, JList, Override (+3 more)

### Community 55 - ".buildUI"
Cohesion: 0.17
Nodes (9): DefaultTableModel, JPanel, Component, DefaultTableModel, JPanel, JTable, KnowledgeBaseTab, Component (+1 more)

### Community 56 - "ReportExportService"
Cohesion: 0.27
Nodes (5): FunctionalInterface, Component, JButton, ReportExportService, ReportWriter

### Community 57 - "Evidence Manager & Reporting feature doc"
Cohesion: 0.15
Nodes (13): Evidence Manager Redesign (Master-Detail UI), Embedded MCP Server (ServerSocket, AI integration), ParamValidator module (v1.4.0 URL/GET query testing), v1.4.0 Release (Evidence Manager redesign, MCP server), Evidence Manager & Reporting feature doc, Offline CWE Tagging, Drag-and-Drop Reordering, One-Click Apply (+5 more)

### Community 58 - "EvidencePhase1Dialog"
Cohesion: 0.24
Nodes (3): EvidencePhase1Dialog, Graphics2D, JTextArea

### Community 59 - "Custom Tools Reference"
Cohesion: 0.15
Nodes (10): Async Tools, Best Practices, Custom Caching, Custom Tools Reference, Method 1: @tool Decorator (Simple), Method 2: BaseTool Subclass (Full Control), Tool Assignment, With BaseTool (Both Sync and Async) (+2 more)

### Community 60 - "Text"
Cohesion: 0.23
Nodes (5): Border, com.formdev.flatlaf.extras.FlatSVGIcon, FlatSVGIcon, Icon, Text

### Community 61 - "PassiveErrorModule"
Cohesion: 0.26
Nodes (5): burp.api.montoya.core.ByteArray, VerboseErrorDetector, Override, PassiveErrorModule, java.util.regex.Pattern

### Community 62 - "FindingField"
Cohesion: 0.20
Nodes (9): FindingField, DESCRIPTION, EVIDENCE, HOW, IMPACT, REMEDIATION, WHEN, WHERE (+1 more)

### Community 63 - "Flow Routing Reference"
Cohesion: 0.17
Nodes (12): 10. Flow Visualization, 1. Basic Router — Conditional Branching, 2. or_() — Fire on ANY Upstream Completion, 3. and_() — Fire When ALL Upstreams Complete, 4. Nested Conditions, 5. Revision Loop Pattern, 6. Conditional Starts, 7. Flow Persistence with @persist (+4 more)

### Community 64 - "Dynamic Report Engine (docs)"
Cohesion: 0.24
Nodes (10): Dynamic Report Engine (docs), Export Formats (HTML/PDF), Master-Detail Markdown Editor, Report Profiles, Retest Mode, Sections Flow UI, Themes and Branding, Dynamic Report Engine (+2 more)

### Community 65 - "ToastNotification"
Cohesion: 0.13
Nodes (12): Frame, Annotation, BufferedImage, Color, Graphics2D, Shape, Color, Rectangle (+4 more)

### Community 66 - "7. Flows — The Production Foundation"
Cohesion: 0.17
Nodes (12): 7. Flows — The Production Foundation, Agent.kickoff() with Structured Output in Flows, Basic Flow — main.py, Converging Branches with `or_()` and `and_()`, Conversational Flows with `handle_turn()` (Experimental), Flow Persistence with `@persist`, Flow Routing with `@router`, Flow Visualization (+4 more)

### Community 67 - "ICARUS Development Guide"
Cohesion: 0.17
Nodes (11): Build & Run Commands, Coding Guidelines, graphify, ICARUS Development Guide, Key Tools, MCP Tools: code-review-graph, Porting to MUNINN, Project Structure (+3 more)

### Community 68 - ".render"
Cohesion: 0.29
Nodes (4): EvidenceAutoRenderer, BufferedImage, Rectangle, java.awt.Rectangle

### Community 69 - "RateLimitModule (concept)"
Cohesion: 0.33
Nodes (6): Rate Limit Tester Module Doc, Phase 1: Burst Detection, Phase 3: Bypass Attempts (IP spoofing headers), Phase 2: Characterization, RateLimitModule (concept), RateLimitModule

### Community 70 - "AstLeaf"
Cohesion: 0.17
Nodes (4): AstLeaf, AstSerializer, SerializedResult, Override

### Community 71 - "Transition"
Cohesion: 0.25
Nodes (6): StatusTransition, Transition, BYPASS, ERROR, NONE, SESSION_LOST

### Community 72 - ".renderPdf"
Cohesion: 0.29
Nodes (5): ElevatedCardFindingRenderer, Document, Override, PdfPTable, PdfWriter

### Community 73 - ".renderPdf"
Cohesion: 0.29
Nodes (4): Document, Override, PdfWriter, TabularFindingRenderer

### Community 74 - "GradientHeroCoverRenderer"
Cohesion: 0.27
Nodes (5): GradientHeroCoverRenderer, Document, Override, PdfContentByte, PdfWriter

### Community 75 - "ICARUS Documentation Index"
Cohesion: 0.15
Nodes (18): Getting Started guide, build.sh (getting started compile step), ICARUS Documentation Index, Pull Request Template, build.sh (PR checklist requirement), Conventional Commits format, Build GitHub Actions Workflow, build.sh (CI build step) (+10 more)

### Community 76 - ".buildReportData"
Cohesion: 0.21
Nodes (4): Cwe, CweRepository, SuppressWarnings, ReportDataMapper

### Community 77 - ".addCweChip"
Cohesion: 0.40
Nodes (3): Color, JButton, JPanel

### Community 78 - "ColorsThemeSectionPanel"
Cohesion: 0.26
Nodes (7): ColorsThemeSectionPanel, Component, JComboBox, JComponent, JPanel, JSpinner, Override

### Community 80 - "HeaderBandCoverRenderer"
Cohesion: 0.33
Nodes (4): HeaderBandCoverRenderer, Document, Override, PdfWriter

### Community 81 - "Ask CrewAI Docs"
Cohesion: 0.18
Nodes (10): Ask CrewAI Docs, Examples of Good Use Cases, For an Even Better Experience, How to Query the Docs, Related Skills, Step 1: Fetch the docs index, Step 2: Fetch the relevant page, Step 3: Synthesize and cite (+2 more)

### Community 82 - "Memory & Knowledge Reference"
Cohesion: 0.18
Nodes (11): 1. Memory Configuration, 4. Storage Locations, 5. Common Patterns, Agent with Tools + Knowledge, Basic — Enable Default Memory, Crew with Shared Memory + Per-Agent Knowledge, Custom Memory Configuration, Memory in Flows (+3 more)

### Community 83 - "AutoAuth Engine feature doc"
Cohesion: 0.29
Nodes (7): AutoAuthModule (interceptor), Montoya HttpHandler, AutoAuth Engine feature doc, Host-Scoped constraint prevents cross-host token leakage, Persistent Storage of source/destination mappings, Silent Background Refresh, Source-Destination Mapping Model

### Community 84 - "burp.api.montoya.http.message.HttpRequestResponse"
Cohesion: 0.23
Nodes (5): burp.api.montoya.http.message.HttpRequestResponse, EvidenceTriggerService, BufferedImage, Image, Override

### Community 85 - ".getString"
Cohesion: 0.33
Nodes (3): JScrollPane, JTextArea, JTextArea

### Community 86 - "Core Testing Workflows Doc"
Cohesion: 0.47
Nodes (6): Core Testing Workflows Doc, Context Menu Integration, Managing Active Scans (Active Tasks view), Reviewing Findings (Results tab), EvidenceCapture, EvidenceColorScheme

### Community 87 - "Security Checks GitHub Actions Workflow"
Cohesion: 0.33
Nodes (6): Security Checks GitHub Actions Workflow, build.sh (security workflow build step), CodeQL Analysis, Dependency Review Action, Gitleaks Secret Scanning, Semgrep (p/java ruleset)

### Community 88 - "Finding"
Cohesion: 0.40
Nodes (5): Category, Finding, FindingRecord, FindingRegistry, Severity

### Community 90 - "Graphify Knowledge Graph"
Cohesion: 0.50
Nodes (4): graphify-out/graph.json, Graphify Knowledge Graph, Graphify Skill, Graphify Workflow

### Community 91 - "FooterPageEvent"
Cohesion: 0.47
Nodes (3): FooterPageEvent, Image, PdfPageEventHelper

### Community 92 - ".renderFinalImage"
Cohesion: 0.50
Nodes (3): BufferedImage, Color, Shape

### Community 93 - "2. Agent Configuration Reference"
Cohesion: 0.18
Nodes (11): 2. Agent Configuration Reference, Agent Guardrails, Code Execution, Collaboration, Context Window Management, Date Injection, Essential Parameters, Execution Control (+3 more)

### Community 98 - "CrewAI Agent Design Guide"
Cohesion: 0.20
Nodes (10): 3. YAML Configuration (Recommended), 5. Specialist vs Generalist Agents, 7. Common Agent Design Mistakes, 8. Agent Design Checklist, CrewAI Agent Design Guide, References, Specialist Design Pattern, The 80/20 Rule (+2 more)

### Community 100 - "Conversational Flows Reference"
Cohesion: 0.20
Nodes (10): Agents Inside Conversational Routes, API Selection, Common Mistakes, Conversational Flows Reference, ConversationConfig, Mental Model, Minimal Experimental Flow, Persistence and Tracing (+2 more)

### Community 101 - "BrandingSectionPanel"
Cohesion: 0.36
Nodes (5): BrandingSectionPanel, Component, JPanel, JTextField, Override

### Community 112 - "SectionFlowPanel"
Cohesion: 0.28
Nodes (5): Component, JComponent, JPanel, Override, SectionFlowPanel

### Community 113 - "ToolbarPanel"
Cohesion: 0.25
Nodes (6): Component, JButton, JComboBox, JPanel, Override, ToolbarPanel

### Community 114 - "3. Embedder Configuration"
Cohesion: 0.25
Nodes (8): 3. Embedder Configuration, Azure OpenAI, Cohere, Google, HuggingFace (Local), Ollama (Local), OpenAI (Default), VoyageAI (Recommended for Claude)

### Community 115 - "4. Agent.kickoff() — Direct Agent Execution"
Cohesion: 0.29
Nodes (7): 4. Agent.kickoff() — Direct Agent Execution, Agent.kickoff() in Conversational Flow Routes, Agent.kickoff() in Flows (Recommended Pattern), Async Variant, Basic Usage, With File Inputs, With Structured Output

### Community 116 - "📢 ICARUS 1.0: The Complete Offensive Pipeline (Official Launch)"
Cohesion: 0.29
Nodes (6): ⚙️ Core Offensive Modules, 📄 Dynamic Report Engine, 📢 ICARUS 1.0: The Complete Offensive Pipeline (Official Launch), 🗂️ Master-Detail Evidence Manager, 🤖 Native AI Agent Integration (MCP), ⚡ The ParamValidator & Advanced WAF Evasion

### Community 132 - "ContentPolicy"
Cohesion: 0.29
Nodes (4): ContentPolicy, CweMode, HARDCODED_CATALOG, PROFILE_LIST

### Community 133 - "2. Knowledge Sources"
Cohesion: 0.33
Nodes (6): 2. Knowledge Sources, Assigning Knowledge to Agents, Assigning Knowledge to Crews (Shared), File-Based Knowledge, Knowledge Configuration, String Knowledge

### Community 135 - "Planning (Plan-and-Execute Mode)"
Cohesion: 0.33
Nodes (6): Cost shape, Custom `plan_prompt`, Other `PlanningConfig` knobs, Planning (Plan-and-Execute Mode), `reasoning_effort` — pick one, When to enable

### Community 136 - "0. How Many Agents Do You Actually Need?"
Cohesion: 0.40
Nodes (5): 0. How Many Agents Do You Actually Need?, Anti-pattern: Sequential mechanical steps as separate agents, Anti-pattern: "Summarize then send" as two agents, Heuristic, Once you've decided "one agent is enough"

### Community 137 - "Contributor Covenant Code of Conduct"
Cohesion: 0.08
Nodes (23): 1. Correction, 2. Warning, 3. Temporary Ban, 4. Permanent Ban, Attribution, Contributor Covenant Code of Conduct, Enforcement, Enforcement Guidelines (+15 more)

### Community 140 - "1. The Role-Goal-Backstory Framework"
Cohesion: 0.50
Nodes (4): 1. The Role-Goal-Backstory Framework, Backstory — Why the Agent Is Qualified, Goal — What the Agent Wants, Role — Who the Agent Is

### Community 141 - "6. Agent Interaction Patterns"
Cohesion: 0.50
Nodes (4): 6. Agent Interaction Patterns, Agent-to-Agent Delegation, Hierarchical, Sequential (Default)

## Ambiguous Edges - Review These
- `CweRepository` → `VerboseErrorDetector`  [AMBIGUOUS]
  icarus-extension/docs_generated.md · relation: conceptually_related_to
- `Icarus (extension entry point)` → `Orchestrator`  [AMBIGUOUS]
  icarus-extension/docs_generated.md · relation: conceptually_related_to

## Knowledge Gaps
- **363 isolated node(s):** `/usr/bin/python3`, `build.sh script`, `com.icarus:icarus`, `HEADER`, `BODY` (+358 more)
  These have ≤1 connection - possible missing edges or undocumented components. (Counts symbols only; 582 node(s) total have ≤1 connection when file, concept and rationale nodes are included.)
- **33 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **What is the exact relationship between `CweRepository` and `VerboseErrorDetector`?**
  _Edge tagged AMBIGUOUS (relation: conceptually_related_to) - confidence is low._
- **What is the exact relationship between `Icarus (extension entry point)` and `Orchestrator`?**
  _Edge tagged AMBIGUOUS (relation: conceptually_related_to) - confidence is low._
- **Why does `ModuleConfig` connect `ModuleConfig` to `AutoAuthModule`, `burp.api.montoya.http.message.requests.HttpRequest`, `PdfReportGenerator`, `EvidenceCapture`, `ReportTemplateConfig`, `.saveTo`, `ReportingSettingsTab`, `ScanRunner`, `.buildUI`, `FindingRegistry`, `PostmanExportModule`, `.run`, `Severity`, `EvidenceImageRenderer`, `EvidenceTriggerService.java`, `Orchestrator`, `I18n`, `ReportGenerator`, `.EvidenceManagerTab`, `.getBool`, `ReportProfile`, `SensitiveHeaderModule`, `ReportExportService`, `EvidencePhase1Dialog`, `PassiveErrorModule`, `.buildReportData`, `burp.api.montoya.http.message.HttpRequestResponse`, `.getString`?**
  _High betweenness centrality (0.122) - this node is a cross-community bridge._
- **Why does `Category` connect `Category` to `I18n`, `.render`, `Finding`, `Transition`, `.run`, `OffensiveAstRoot`, `ModuleConfig`, `SensitiveHeaderModule`, `Builder`, `EvidenceTriggerService.java`?**
  _High betweenness centrality (0.108) - this node is a cross-community bridge._
- **Why does `ReportingSettingsTab` connect `ReportingSettingsTab` to `.addFormRow`, `ContentSectionPanel`, `ThemeHelper`, `I18n`, `BrandingSectionPanel`, `com.fasterxml.jackson.annotation.JsonIgnoreProperties`, `SectionListPanel`, `ReportProfileManager`, `ReportProfile`, `.buildUI`, `ColorsThemeSectionPanel`, `SectionFlowPanel`, `ToolbarPanel`, `ModuleConfig`, `Severity`, `DetailPane`, `WireframeKind`?**
  _High betweenness centrality (0.103) - this node is a cross-community bridge._
- **What connects `/usr/bin/python3`, `build.sh script`, `com.icarus:icarus` to the rest of the system?**
  _363 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `AutoAuthModule` be split into smaller, more focused modules?**
  _Cohesion score 0.05561105561105561 - nodes in this community are weakly interconnected._