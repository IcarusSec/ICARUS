package icarus.mcp;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import icarus.core.Severity;
import icarus.Icarus;
import icarus.Orchestrator;
import icarus.core.Finding;
import icarus.core.FindingRecord;
import icarus.core.JsonParser;
import icarus.core.ProjectContextDetector;
import icarus.core.ReportTemplateConfig;
import icarus.evidence.EvidenceCapture;
import icarus.modules.ParamValidatorModule;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;

import javax.imageio.ImageIO;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Local MCP (Model Context Protocol) server embedded in the extension, so AI agents (Claude
 * Desktop and other MCP clients) can query ICARUS findings directly. Runs over Streamable HTTP —
 * stdio is unavailable since Burp owns the JVM's stdio for its own logging — via
 * {@link IcarusMcpTransportProvider}, a transport hand-written against a raw {@code ServerSocket}:
 * the SDK's ready-made transport is a jakarta.servlet HttpServlet, which would need an embedded
 * servlet container (Jetty, ~8 more jars) just to run one servlet inside a Burp extension whose
 * build has no dependency resolver.
 *
 * <p>Off by default (config key {@code mcp.enabled}, toggled in Settings). Bound to 127.0.0.1
 * only, unauthenticated — loopback binding is the security boundary, so any local process can
 * already reach it and a bearer token would add nothing but friction to an AI agent's config.
 *
 * <p>JSON mapping uses the SDK's own {@link JacksonMcpJsonMapper} (correct record&lt;-&gt;JSON
 * round-tripping) rather than hand-rolling one against this repo's {@link JsonParser} — but
 * schema validation deliberately does NOT use the SDK's networknt-backed validator (it drags in
 * jackson-dataformat-yaml, snakeyaml, and itu just to validate JSON Schema); see
 * {@link IcarusJsonSchemaValidator}.
 *
 * <p>Tool surface mirrors what {@link Orchestrator} exposes non-interactively (everything else
 * on that facade drives a Swing dialog/file-chooser, which has no meaning to a headless MCP
 * client).
 */
public final class IcarusMcpServer {

    private final MontoyaApi api;
    private final Orchestrator orchestrator;

    private IcarusMcpTransportProvider transportProvider;
    private McpSyncServer server;
    private int port = -1;

    /**
     * Server-level guidance sent to every connected client — grounded in exactly what's
     * implemented below, not aspirational. Written to stop the two failure modes actually
     * observed in practice: an agent supplying image_base64/request_text when it didn't need to
     * (destroying evidentiary value), and guessing pixel coordinates for text whose position
     * can't be known ahead of render time.
     */
    private static final String MCP_INSTRUCTIONS = """
            # Role: Senior Offensive Security Specialist / Lead Penetration Tester

            **Objective:** Perform automated project identification and classification, technical vulnerability validation, impact escalation (PoC), triage and false-positive suppression, dynamic customization of report sections and templates via MCP, contextual enrichment of findings, and compilation of the final executive report (PDF) in the ICARUS standard.

            ---

            ### [GENERAL EXECUTION GUIDELINES]

            - Adopt a strictly technical, formal, analytical, business-risk-oriented tone.
            - Follow the sequential flow of steps 1 through 7 rigorously.
            - Actively use the ICARUS MCP toolset (`icarus.mcp`) for inspection, manipulation, and configuration.
            - Sanitize strings and metadata containing special characters to avoid breaking the PDF/HTML renderer.

            ---

            ### 1. Project Context, Scope & Metadata Auto-Detection

            1. **Automated Discovery via MCP:**
               - Run the `get_project_context` tool (or inspect the `auto_detected_context` object returned by `get_report_config`) to get a heuristic diagnosis of the environment.
               - Validate the detected data against your organization's own classification rules. `ProjectContextDetector` extracts a generic `PREFIX-1234`-style project code by default — adapt its pattern/classification logic to match your organization's actual project-code conventions:
                 - **Test Type Classification:** derive from your project-code pattern, or fall back to a generic **"Offensive Security Assessment"** and ask the user to confirm if the pattern doesn't match anything known.
                 - **Environment Detection:** if the hosts/URLs contain `uat`, `homol`, `dev`, `staging`, or a `-h.` suffix, classify as **`UAT / Staging`**. If they match a known production hostname pattern for this engagement, classify as **`Production`**.

            2. **Report Metadata & Responsibility Matrix:**
               - **Project / Code:** the identifier extracted above (e.g. `PROJ-1234`).
               - **Report Title:** `Technical Security Report - [Project] - [Test Type]`
               - **Author / Executor / Owner:** ask the user, or derive from the local system/VCS identity — never hardcode a name.
               - **Approver:** ask the user; leave blank if not provided.
               - **Requester / Team:** the team or requesting party for this engagement, if known.
               - **Date:** today's date, `DD/MM/YYYY`.
               - **Method:** e.g. `Black Box / Greybox`.
               - **Classification:** e.g. `Confidential`.
               - **Reviewer:** look for a reviewer's name in project history; if not explicit, ask the user interactively without interrupting the rest of the flow.

            ---

            ### 2. Dynamic Section Management & Template Customization (MCP)

            1. **Inspect the Current Configuration:**
               - Call `get_report_config` to inspect the active sections (`sections`), persisted variables (`templateVariables`), and finding templates (`findingTemplates`).

            2. **Granular Section Management (`update_report_config`):**
               - **Editing Mandatory Sections:** update the Markdown content of mandatory sections (*Executive Summary*, *Document Control*, *Scope*) via `update_section`. *(Mandatory sections accept content updates but are protected against deletion.)*
               - **Adding Custom Sections:** add sections relevant to the engagement's scope (e.g. *Solution Architecture*, *Specific Methodology*, *UAT Environment Limitations*) via `add_section`, specifying `title`, `content`, and optionally `index`.
               - **Markdown support in section `content`:** CommonMark — headings, **bold**, `code`, fenced ``` blocks, ordered/unordered lists — plus GFM pipe tables (`| a | b |` / `|---|---|`). Pipe tables render as real tables in the **HTML** report only; the **PDF** renderer drops them to plain text. If the deliverable is a PDF (or you don't know), write tabular data as nested bullet lists, not pipe tables. An *Attack Narrative* / kill-chain section reads best as `### Step N — <title>` headings with bullets under each, not a table.
               - **Removing Optional Sections:** remove sections not applicable to this test via `remove_section`.

            3. **Standardizing Vulnerability Templates (`finding_templates`):**
               - If you need to standardize descriptions, impacts, or remediations for recurring vulnerability types (e.g. *Insecure CORS*, *BOLA/IDOR*, *Missing Security Headers*), update the `finding_templates` map in `update_report_config` to automatically enrich findings of that type.

            ---

            ### 3. Cross-Analysis & Traffic Inspection

            1. **Initial Findings Mapping:**
               - Run `list_findings` to enumerate every finding in the project (metadata only — type, severity, path, hash).

            2. **Read the actual traffic:**
               - `get_finding` with a hash returns the finding's captured HTTP request AND response (headers + body,
                 body capped at ~16KB) plus its metadata. Read these to understand what was sent, what came back,
                 and whether the signal is real — don't reason from the one-line description alone.
               - `get_finding_traffic` returns the SAME request/response with NO truncation — call it when
                 get_finding's output was marked truncated and you need the rest (pass part="request"/"response"
                 to fetch only one half).
               - `validate_finding` / `exploit_finding` also return the freshly-sent request and fresh response
                 body, so you can diff old vs new.
               - `rescan_finding` re-runs the scan against that endpoint for a clean re-capture.

            ---

            ### 4. Active Validation & Impact Escalation (PoC)

            0. **Rebind stale findings first:** `validate_finding` / `exploit_finding` resend a finding's captured
               request, which needs a live HTTP service. Findings restored from an older project file, or whose
               target was down at save time, come back service-less and fail. Before triaging those, call
               `rescan_finding` with the hash — it resends the finding's own request (right method/body/host) and
               re-runs the active scan, producing fresh findings bound to a live service. It's also the only way to
               re-confirm a `STRING_SSRF` (one-time Collaborator payload — not directly replayable).

            1. **Practical Escalation Protocol:**
               - Prove the maximum real impact of the flaw (don't report purely theoretically):
                 - **IDOR / BOLA / BAC:** manipulate other accounts' identifiers and prove the data segregation break.
                 - **Privilege Escalation:** attempt to hit administrative endpoints using lower-privilege/partner tokens.
                 - **Injection / Business-Logic Bypass:** test schema-validation bypasses, transactional rules, and operational limits.
               - **Sandbox / WAF / Mock Limitations:** if an endpoint responds with static mock/stub data in UAT, or is blocked by a WAF preventing exploitation confirmation, explicitly note that limitation in the report.

            ---

            ### 5. Critical Triage, Suppression & Finding Enrichment

            1. **False-Positive Suppression Criteria:**
               - **Verbose Error Disclosure:** keep `HTTP 500` / `Server Error` findings **only if they expose internal infrastructure data** (full stack traces with code paths, internal regexes, references to config/YAML files, internal library versions). Generic or handled 500 errors should be suppressed.
               - **Mock/Stub Endpoints in UAT:** suppress *Missing Input Validation* alerts if the endpoint accepts any input because there's no real backend behind the mock.
               - **Traceability Headers:** headers like `x-request-id`, `traceparent`, `x-correlation-id` do **not** constitute sensitive information disclosure.
               - **JWT/Crypto Heuristic Alerts:** suppress informational alerts (`RSA_SIG`, `HAS_KID`, `MISSING_NBF`) if contextual analysis shows the token follows the pattern accepted in this engagement's scope.
               - **Action:** for each false positive/accepted risk, call `suppress_finding` and always fill in the `reason` parameter with a technical justification.

            2. **Enriching Individual Confirmed Findings (`update_finding`):**
               - For each vulnerability confirmed as active:
                 - **`description`:** detail the observed architecture at the endpoint (methods, routes, and manipulated parameters), avoiding generic definitions.
                 - **`impact`:** size the business impact for this engagement (e.g. customer data leakage, contract manipulation, transactional fraud, audit bypass) in terms specific to the client's domain.
                 - **`recommendations` / `remediation`:** provide actionable steps that fit the client's actual stack (e.g. validation via the organization's JWT claims, attribute-based access control, sanitization via standard libraries).

            ---

            ### 6. Consolidating & Annotating Technical Evidence

            1. **Linking Proof of Concept:**
               - Make sure every valid finding in `get_reportable_findings` has its evidence HTTP requests/responses properly attached.
               - `capture_evidence` attaches evidence to a finding. It has three input modes, in precedence order:
                 - `image_base64` — a screenshot you already have (browser screenshot of the exploited page, a rendered dashboard, etc.). Any format ImageIO reads.
                 - `code` — the VERBATIM output of an external tool you ran to validate the finding (sqlmap, nuclei, ffuf, a curl transcript, a decoded JWT, a snippet of vulnerable source). ICARUS renders it into a monospace evidence image and attaches it like any screenshot. Use `title` to label it. Paste the real output, don't summarise.
                 - neither — ICARUS auto-renders the evidence image from the finding's own captured request/response (the default, preferred path).
               - Attach as many evidence items per finding as the PoC needs — e.g. the auto-rendered traffic shot PLUS a `code` block with the sqlmap run PLUS a browser screenshot. They render in the finding's card in `list_evidence` order (reorder with `reorder_evidence`).
               - `get_evidence` / `list_evidence` show what's already attached; `set_evidence_caption`, `set_evidence_included`, `remove_evidence` manage them.

            2. **Annotation (optional).** ICARUS-rendered evidence images ship clean by default. If a box/arrow genuinely
               helps a reader find the proof (a payload buried in a busy response, say), pass `annotations` — otherwise
               skip it; a clean traffic shot is fine. When you do annotate:
               - **Target with `anchor`, never guessed x/y.** Pick the tightest anchor that covers the proof:
                 - injection findings (STRING_SQLI, STRING_XSS, STRING_CMDI, ...): `response_payload`, or `request_payload` for a body parameter.
                 - single-header findings (VERSION_DISCLOSURE, ...): `response_header:<name>` (lowercase).
                 - missing-header findings (MISSING_*): `response_headers` (the whole block — the header is absent, nothing to point at).
                 - server errors (SERVER_ERROR): `response_status_line`.
                 - rate-limit findings: `rps` and/or `blocked`.
               - **Pattern:** one `BOX` on the target anchor plus one `ARROW` on the same anchor — the arrow is
                 auto-pointed at the region's edge, so `[{"kind":"BOX","anchor":"response_payload"},{"kind":"ARROW","anchor":"response_payload"}]`
                 is the normal case. Add a `CROP` (listed LAST) only to trim a large noisy body.
               - To hide a secret/token/PII, do NOT paint over it — redact it in the `request_text`/`response_text`
                 override (replace the value in place, keep every other line verbatim). There is no black-box kind.
               - `capture_evidence`'s result echoes the anchor names that actually existed for that render — read it.
                 If the anchor you wanted is absent (e.g. `response_payload` fell outside a long truncated body),
                 fall back to the next-loosest anchor that IS listed (`response_headers` → `response_column`), or add a
                 `CROP` around the relevant lines so the payload is on-screen, then re-run with the tighter anchor.
               - Only fall back to raw x/y for an `image_base64` screenshot you took yourself (no anchors exist for those);
                 estimate from the pixels you can see.

            ---

            ### 7. Executive Report Generation (PDF) & Chat Presentation

            1. **Compiling via ICARUS (`generate_icarus_report`):**
               - Call the `generate_icarus_report` tool with `format: "pdf"`, explicitly providing every needed template variable:
                 ```json
                 {
                   "format": "pdf",
                   "project": "PROJ-1234",
                   "report_title": "Technical Security Report - PROJ-1234 - API Test",
                   "date": "28/08/2026",
                   "assessment_period": "24/08/2026 to 28/08/2026",
                   "method": "Black Box / Greybox",
                   "classification": "Confidential",
                   "version": "1.0",
                   "author": "[Author name]",
                   "owner": "[Owner name]",
                   "approver": "[Approver name]",
                   "reviewer": "[Reviewer name]",
                   "requester": "[Requesting team]",
                   "team": "[Team]",
                   "environment": "UAT / Staging"
                 }
                 ```

            2. **Executive Summary Presentation in Chat:**
               - After generating the PDF, present a structured, visual, direct summary in the chat containing:
                 - **Metadata Table:** Project, Test Type, Environment, Period, and Responsibility Matrix (Author, Reviewer, Approver).
                 - **Sections & Customizations Summary:** which sections were created/edited and which templates were applied.
                 - **Triage Metrics:** total raw findings vs. total suppressed findings (with a summary of the justifications).
                 - **Escalation & Real Impact Summary:** which escalation tests were performed and their results.
                 - **Active Findings Table:** a table with ID, Vulnerability, Severity (Critical, High, Medium, Low, Info), and Affected Endpoint.
                 - **Final File:** the absolute path or link to the PDF file ICARUS generated.
            """;

    public IcarusMcpServer(MontoyaApi api, Orchestrator orchestrator) {
        this.api = api;
        this.orchestrator = orchestrator;
    }

    public synchronized boolean isRunning() {
        return server != null;
    }

    public synchronized String statusSummary() {
        return isRunning() ? "Running on http://127.0.0.1:" + port + "/mcp" : "Stopped";
    }

    /** Starts on an ephemeral port (0 = OS-assigned), same as historical behavior. */
    public synchronized void start() {
        start(0);
    }

    /** Starts on {@code requestedPort} (0 = OS-assigned ephemeral port). */
    public synchronized void start(int requestedPort) {
        if (server != null) return;
        try {
            ObjectMapper objectMapper = new ObjectMapper()
                    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
            McpJsonMapper jsonMapper = new JacksonMcpJsonMapper(objectMapper);

            transportProvider = new IcarusMcpTransportProvider(
                    msg -> api.logging().logToError(msg), jsonMapper, "/mcp");

            McpServerFeatures.SyncToolSpecification[] tools = {
                    listFindingsTool(), addFindingTool(), getFindingTool(), getFindingTrafficTool(), suppressFindingTool(), unsuppressFindingTool(),
                    getAuditLogTool(), getPassiveFindingsTool(), clearPassiveFindingsTool(), clearAllFindingsTool(),
                    getReportableFindingsTool(), triggerScanTool(), rescanFindingTool(), generateReportTool(),
                    getEvidenceTool(), captureEvidenceTool(),
                    listEvidenceTool(), setEvidenceCaptionTool(), setEvidenceIncludedTool(),
                    moveEvidenceTool(), removeEvidenceTool(), reorderEvidenceTool(),
                    getReportConfigTool(), updateReportConfigTool(), getProjectContextTool(),
                    upsertKbVulnerabilityTool(), createFindingFromKbTool(),
                    validateFindingTool(), exploitFindingTool(), findAttackChainsTool(), simulateAttackChainTool()
            };

            server = McpServer.sync(transportProvider)
                    .serverInfo(new McpSchema.Implementation("icarus", Icarus.VERSION))
                    .instructions(MCP_INSTRUCTIONS)
                    .jsonMapper(jsonMapper)
                    .jsonSchemaValidator(new IcarusJsonSchemaValidator())
                    .capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
                    .tools(tools)
                    .build();

            port = transportProvider.start(requestedPort);
            api.logging().logToOutput("ICARUS MCP server listening on http://127.0.0.1:" + port + "/mcp");
            api.logging().logToOutput("ICARUS MCP tools (" + tools.length + "): " + java.util.Arrays.stream(tools)
                    .map(t -> t.tool().name())
                    .collect(java.util.stream.Collectors.joining(", ")));
        } catch (IOException e) {
            api.logging().logToError("Failed to start ICARUS MCP server: " + e);
            server = null;
            transportProvider = null;
        }
    }

    public synchronized void stop() {
        if (server == null) return;
        server.closeGracefully();
        transportProvider.stop();
        server = null;
        transportProvider = null;
    }

    private McpServerFeatures.SyncToolSpecification listFindingsTool() {
        var inputSchema = new McpSchema.JsonSchema("object",
                Map.of("severity_filter", Map.of(
                        "type", "string",
                        "description", "Optional severity to filter by: CRITICAL, HIGH, MEDIUM, LOW, or INFO")),
                List.of(), false, null, null);
        var tool = new McpSchema.Tool("list_findings",
                "List ICARUS findings",
                "Lists all security findings ICARUS has detected in this Burp project, optionally filtered by severity.",
                inputSchema, null, null, null);

        return new McpServerFeatures.SyncToolSpecification(tool, (exchange, request) -> {
            var args = request.arguments() != null ? request.arguments() : Map.of();
            Object rawFilter = args.get("severity_filter");
            String severityFilter = rawFilter instanceof String s ? s : null;

            List<Object> findings = new ArrayList<>();
            for (FindingRecord record : orchestrator.getAllFindingRecords()) {
                if (severityFilter != null && !record.getFinding().severity().name().equalsIgnoreCase(severityFilter)) continue;
                findings.add(findingToMap(record.getFinding(), record));
            }

            return McpSchema.CallToolResult.builder().addTextContent(JsonParser.write(findings)).build();
        });
    }

    private McpServerFeatures.SyncToolSpecification getFindingTool() {
        var inputSchema = new McpSchema.JsonSchema("object",
                Map.of("hash", Map.of("type", "string", "description", "The finding's similarityHash, as returned by list_findings")),
                List.of("hash"), false, null, null);
        var tool = new McpSchema.Tool("get_finding",
                "Get ICARUS finding detail",
                "Looks up one ICARUS finding by its similarityHash. Returns its full detail INCLUDING the "
                        + "captured HTTP request and response (headers + body, body truncated if very large) and "
                        + "the finding's metadata, so you can read the actual traffic and understand what happened.",
                inputSchema, null, null, null);

        return new McpServerFeatures.SyncToolSpecification(tool, (exchange, request) -> {
            String hash = (String) request.arguments().get("hash");
            Finding finding = orchestrator.getFindingByHash(hash);
            if (finding == null) {
                return McpSchema.CallToolResult.builder().addTextContent("No finding found for hash: " + hash).isError(true).build();
            }
            return McpSchema.CallToolResult.builder().addTextContent(JsonParser.write(findingToMap(finding, null, true))).build();
        });
    }

    private McpServerFeatures.SyncToolSpecification getFindingTrafficTool() {
        var inputSchema = new McpSchema.JsonSchema("object",
                Map.of("hash", Map.of("type", "string", "description", "The finding's similarityHash, as returned by list_findings"),
                       "part", Map.of("type", "string", "description", "\"request\", \"response\", or \"both\" (default). Ask for only what you need.")),
                List.of("hash"), false, null, null);
        var tool = new McpSchema.Tool("get_finding_traffic",
                "Get a finding's FULL, untruncated HTTP request/response",
                "Returns the complete captured request and/or response for a finding — every header and the entire "
                        + "body, with NO truncation (get_finding caps the body at ~16KB). Use this when get_finding's "
                        + "output was marked truncated and you actually need the rest — e.g. a large JSON/HTML body "
                        + "you have to inspect to confirm or rule out the finding. It can be big, so pass part=\"request\" "
                        + "or part=\"response\" to fetch only the half you need.",
                inputSchema, null, null, null);

        return new McpServerFeatures.SyncToolSpecification(tool, (exchange, request) -> {
            String hash = (String) request.arguments().get("hash");
            Finding finding = orchestrator.getFindingByHash(hash);
            if (finding == null) {
                return McpSchema.CallToolResult.builder().addTextContent("No finding found for hash: " + hash).isError(true).build();
            }
            if (finding.evidence() == null) {
                return McpSchema.CallToolResult.builder().addTextContent("This finding has no captured traffic.").isError(true).build();
            }
            String part = request.arguments().get("part") instanceof String s ? s.toLowerCase() : "both";
            var rr = finding.evidence();
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("hash", hash);
            out.put("type", finding.type());
            if (!"response".equals(part) && rr.request() != null) {
                out.put("request", rr.request().toString());
            }
            if (!"request".equals(part) && rr.response() != null) {
                out.put("response", rr.response().toString());
            }
            return McpSchema.CallToolResult.builder().addTextContent(JsonParser.write(out)).build();
        });
    }

    private McpServerFeatures.SyncToolSpecification suppressFindingTool() {
        var inputSchema = new McpSchema.JsonSchema("object",
                Map.of(
                        "hash", Map.of("type", "string", "description", "The finding's similarityHash, as returned by list_findings"),
                        "reason", Map.of("type", "string", "description", "Why this finding is being suppressed")),
                List.of("hash", "reason"), false, null, null);
        var tool = new McpSchema.Tool("suppress_finding",
                "Suppress ICARUS finding",
                "Marks an ICARUS finding as suppressed, hiding it from reports and the default findings view.",
                inputSchema, null, null, null);

        return new McpServerFeatures.SyncToolSpecification(tool, (exchange, request) -> {
            String hash = (String) request.arguments().get("hash");
            String reason = (String) request.arguments().get("reason");
            orchestrator.suppressFinding(hash, reason);
            return McpSchema.CallToolResult.builder().addTextContent("Suppressed " + hash).build();
        });
    }

    private McpServerFeatures.SyncToolSpecification unsuppressFindingTool() {
        var inputSchema = new McpSchema.JsonSchema("object",
                Map.of("hash", Map.of("type", "string", "description", "The finding's similarityHash, as returned by list_findings")),
                List.of("hash"), false, null, null);
        var tool = new McpSchema.Tool("unsuppress_finding",
                "Unsuppress ICARUS finding",
                "Reverses suppress_finding, making the finding visible in reports and the default findings view again.",
                inputSchema, null, null, null);

        return new McpServerFeatures.SyncToolSpecification(tool, (exchange, request) -> {
            String hash = (String) request.arguments().get("hash");
            orchestrator.unsuppressFinding(hash);
            return McpSchema.CallToolResult.builder().addTextContent("Unsuppressed " + hash).build();
        });
    }

    private McpServerFeatures.SyncToolSpecification getAuditLogTool() {
        var inputSchema = new McpSchema.JsonSchema("object", Map.of(), List.of(), false, null, null);
        var tool = new McpSchema.Tool("get_audit_log",
                "Get ICARUS audit log",
                "Returns the audit log of finding lifecycle events (suppressions, deduplication, etc.) for this Burp project.",
                inputSchema, null, null, null);

        return new McpServerFeatures.SyncToolSpecification(tool, (exchange, request) ->
                McpSchema.CallToolResult.builder().addTextContent(JsonParser.write(orchestrator.getAuditLog())).build());
    }

    private McpServerFeatures.SyncToolSpecification getPassiveFindingsTool() {
        var inputSchema = new McpSchema.JsonSchema("object", Map.of(), List.of(), false, null, null);
        var tool = new McpSchema.Tool("get_passive_findings",
                "Get ICARUS passive findings",
                "Lists findings detected by ICARUS's always-on passive scanning only (not manual evidence or active scans).",
                inputSchema, null, null, null);

        return new McpServerFeatures.SyncToolSpecification(tool, (exchange, request) -> {
            List<Object> findings = new ArrayList<>();
            for (FindingRecord record : orchestrator.getPassiveFindings()) {
                findings.add(findingToMap(record.getFinding(), record));
            }
            return McpSchema.CallToolResult.builder().addTextContent(JsonParser.write(findings)).build();
        });
    }

    private McpServerFeatures.SyncToolSpecification clearPassiveFindingsTool() {
        var inputSchema = new McpSchema.JsonSchema("object", Map.of(), List.of(), false, null, null);
        var tool = new McpSchema.Tool("clear_passive_findings",
                "Clear ICARUS passive findings",
                "Clears all passively-detected findings from this Burp project. Does not affect manual evidence or reportable findings.",
                inputSchema, null, null, null);

        return new McpServerFeatures.SyncToolSpecification(tool, (exchange, request) -> {
            orchestrator.clearPassiveFindings();
            return McpSchema.CallToolResult.builder().addTextContent("Passive findings cleared.").build();
        });
    }

    private McpServerFeatures.SyncToolSpecification clearAllFindingsTool() {
        var inputSchema = new McpSchema.JsonSchema("object",
                Map.of("confirm", Map.of("type", "boolean", "description", "Must be true — this is irreversible.")),
                List.of("confirm"), false, null, null);
        var tool = new McpSchema.Tool("clear_all_findings",
                "Wipe the entire ICARUS findings registry for this project",
                "Removes EVERY finding — active, passive AND evidence-backed — from this Burp project and overwrites "
                        + "the persisted project state, so an extension reload or Burp restart starts clean. Captured "
                        + "evidence screenshots are discarded too; suppression rules are kept. Irreversible — pass "
                        + "confirm=true. Use when the registry has accumulated stale findings from earlier scans or "
                        + "sessions (the registry is per-project and survives extension reloads).",
                inputSchema, null, null, null);

        return new McpServerFeatures.SyncToolSpecification(tool, (exchange, request) -> {
            if (!Boolean.TRUE.equals(request.arguments().get("confirm"))) {
                return McpSchema.CallToolResult.builder()
                        .addTextContent("Nothing wiped — call again with confirm=true to proceed.").isError(true).build();
            }
            long n = orchestrator.getAllFindingRecords().stream()
                    .filter(r -> !"DUMMY".equals(r.getFinding().type())).count();
            orchestrator.clearAllFindings();
            return McpSchema.CallToolResult.builder()
                    .addTextContent("Wiped " + n + " findings and all captured evidence from the project registry. Suppression rules kept.").build();
        });
    }

    private McpServerFeatures.SyncToolSpecification getReportableFindingsTool() {
        var inputSchema = new McpSchema.JsonSchema("object", Map.of(), List.of(), false, null, null);
        var tool = new McpSchema.Tool("get_reportable_findings",
                "Get ICARUS reportable findings",
                "Lists the findings that would be included in a generated report: manually captured/applied evidence, not every passive detection "
                        + "(see generate_icarus_report's description).",
                inputSchema, null, null, null);

        return new McpServerFeatures.SyncToolSpecification(tool, (exchange, request) -> {
            List<Object> findings = new ArrayList<>();
            for (Finding finding : orchestrator.getReportableFindings()) {
                findings.add(findingToMap(finding, null));
            }
            return McpSchema.CallToolResult.builder().addTextContent(JsonParser.write(findings)).build();
        });
    }

    private McpServerFeatures.SyncToolSpecification triggerScanTool() {
        var inputSchema = new McpSchema.JsonSchema("object",
                Map.of("url", Map.of("type", "string", "description", "URL to fetch and scan, e.g. https://example.com/api/users")),
                List.of("url"), false, null, null);
        var tool = new McpSchema.Tool("trigger_scan",
                "Trigger ICARUS active scan",
                "Sends a live GET request to the given URL and runs ICARUS's active scan modules against the response. Runs asynchronously; poll list_findings for results.",
                inputSchema, null, null, null);

        return new McpServerFeatures.SyncToolSpecification(tool, (exchange, request) -> {
            String url = (String) request.arguments().get("url");
            HttpRequestResponse result;
            try {
                result = api.http().sendRequest(HttpRequest.httpRequestFromUrl(url));
            } catch (Exception e) {
                return McpSchema.CallToolResult.builder().addTextContent("Failed to fetch " + url + ": " + e.getMessage()).isError(true).build();
            }
            orchestrator.runScan(result, true);
            return McpSchema.CallToolResult.builder().addTextContent("Scan triggered for " + url).build();
        });
    }

    private McpServerFeatures.SyncToolSpecification rescanFindingTool() {
        var inputSchema = new McpSchema.JsonSchema("object",
                Map.of("hash", Map.of("type", "string", "description", "The finding's similarityHash, as returned by list_findings")),
                List.of("hash"), false, null, null);
        var tool = new McpSchema.Tool("rescan_finding",
                "Re-run ICARUS's active scan against the endpoint a finding came from",
                "Resends the finding's OWN captured request live — correct method, body, headers and target — then runs the full active scan on the "
                        + "response. Use this to rebind stale injection/SSRF findings to a live HTTP service (so validate_finding/exploit_finding work "
                        + "afterwards) and to get a fresh Burp Collaborator confirmation for STRING_SSRF, which can't be replayed directly. Unlike "
                        + "trigger_scan it needs no URL — the target is taken from the finding — but note the captured request still carries the "
                        + "original payload in one field; the scan mutates every field independently so this is a weird baseline, not a blocker. "
                        + "Runs asynchronously; poll list_findings for the newly bound results.",
                inputSchema, null, null, null);

        return new McpServerFeatures.SyncToolSpecification(tool, (exchange, request) -> {
            String hash = (String) request.arguments().get("hash");
            Finding finding = orchestrator.getFindingByHash(hash);
            if (finding == null) {
                return McpSchema.CallToolResult.builder().addTextContent("No finding found for hash: " + hash).isError(true).build();
            }
            if (finding.evidence() == null || finding.evidence().request() == null) {
                return McpSchema.CallToolResult.builder().addTextContent("This finding has no captured request to rescan from.").isError(true).build();
            }
            HttpRequest req = withRebuiltService(finding.evidence().request());
            if (req == null) {
                return McpSchema.CallToolResult.builder().addTextContent(
                        "This finding has no target binding and no Host header to rebuild one — use trigger_scan with an explicit URL instead.").isError(true).build();
            }
            HttpRequestResponse result;
            try {
                result = api.http().sendRequest(req);
            } catch (Exception e) {
                return McpSchema.CallToolResult.builder().addTextContent("Resend failed: " + e.getMessage()).isError(true).build();
            }
            if (result == null || result.response() == null) {
                return McpSchema.CallToolResult.builder().addTextContent(
                        "No response from " + req.httpService().host() + ":" + req.httpService().port() + " — target may be down.").isError(true).build();
            }
            orchestrator.runScan(result, true);
            return McpSchema.CallToolResult.builder().addTextContent(
                    "Rescan triggered against " + req.method() + " " + req.httpService().host() + ":" + req.httpService().port()
                            + req.path() + " (from finding " + finding.type() + "). Poll list_findings.").build();
        });
    }

    private static String sanitizeVariableInput(String input) {
        if (input == null) return null;
        return input.replace("&", "&amp;")
                    .replace("<", "&lt;")
                    .replace(">", "&gt;");
    }

    private McpServerFeatures.SyncToolSpecification generateReportTool() {
        Map<String, Object> stringProp = Map.of("type", "string");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("format", Map.of("type", "string", "description", "\"html\" or \"pdf\" (default \"html\")"));
        for (String key : List.of("classification", "report_title", "project", "version", "date", "author", "reviewer",
                "approver", "team", "component", "requester", "owner", "environment", "assessment_period", "method")) {
            properties.put(key, stringProp);
        }
        var inputSchema = new McpSchema.JsonSchema("object", properties, List.of(), false, null, null);
        var tool = new McpSchema.Tool("generate_icarus_report",
                "Generate ICARUS report",
                "Compiles currently reportable findings into an HTML or PDF report, hydrating template "
                        + "fields (classification, report_title, project, date, team, requester, etc.) "
                        + "from arguments. Accepts any template variable key.",
                inputSchema, null, null, null);

        return new McpServerFeatures.SyncToolSpecification(tool, (exchange, request) -> {
            Map<String, Object> args = request.arguments();
            String format = args.get("format") instanceof String s ? s : "html";

            Map<String, String> templateVariables = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : args.entrySet()) {
                if ("format".equalsIgnoreCase(entry.getKey())) continue;
                if (entry.getValue() instanceof String s && !s.isBlank()) {
                    templateVariables.put(entry.getKey(), sanitizeVariableInput(s));
                }
            }

            try {
                Path written = orchestrator.generateReport(format, templateVariables);
                if (written == null) {
                    return McpSchema.CallToolResult.builder()
                            .addTextContent("No report was written — the reportable set is empty. The report renders the Evidence "
                                    + "Manager's included findings (see get_reportable_findings), not the whole findings registry. "
                                    + "Run capture_evidence for the findings you want in the deliverable first.")
                            .isError(true)
                            .build();
                }
                return McpSchema.CallToolResult.builder().addTextContent(written.toString()).build();
            } catch (Exception e) {
                api.logging().logToError("MCP generate_icarus_report failed: " + e);
                return McpSchema.CallToolResult.builder()
                        .addTextContent("Report generation failed: " + e.getMessage())
                        .isError(true)
                        .build();
            }
        });
    }

    private McpServerFeatures.SyncToolSpecification getEvidenceTool() {
        var inputSchema = new McpSchema.JsonSchema("object",
                Map.of("hash", Map.of("type", "string", "description", "The finding's similarityHash, as returned by list_findings")),
                List.of("hash"), false, null, null);
        var tool = new McpSchema.Tool("get_evidence",
                "Get ICARUS evidence images",
                "Returns the captured evidence screenshots (base64-encoded PNG, with captions) already attached to a finding, so they can be viewed or re-annotated with capture_evidence.",
                inputSchema, null, null, null);

        return new McpServerFeatures.SyncToolSpecification(tool, (exchange, request) -> {
            String hash = (String) request.arguments().get("hash");
            List<Object> evidence = new ArrayList<>();
            try {
                for (var ce : orchestrator.getEvidenceCapture().getCaptured()) {
                    if (!hash.equals(ce.finding().similarityHash())) continue;
                    ByteArrayOutputStream buf = new ByteArrayOutputStream();
                    ImageIO.write(ce.image(), "png", buf);
                    Map<String, Object> e = new LinkedHashMap<>();
                    e.put("image_path", ce.imagePath().toString());
                    e.put("caption", ce.caption());
                    e.put("included", orchestrator.getEvidenceCapture().isIncluded(ce));
                    e.put("width", ce.image().getWidth());
                    e.put("height", ce.image().getHeight());
                    e.put("image_base64", Base64.getEncoder().encodeToString(buf.toByteArray()));
                    evidence.add(e);
                }
            } catch (IOException e) {
                return McpSchema.CallToolResult.builder().addTextContent("Failed to read evidence: " + e.getMessage()).isError(true).build();
            }
            return McpSchema.CallToolResult.builder().addTextContent(JsonParser.write(evidence)).build();
        });
    }

    private McpServerFeatures.SyncToolSpecification captureEvidenceTool() {
        Map<String, Object> annotationItemSchema = Map.of(
                "type", "object",
                "properties", Map.of(
                        "kind", Map.of("type", "string", "description", "BOX, ARROW, or CROP. There is no fill/wash kind — a translucent HIGHLIGHT wash reliably read "
                                + "as a muddy smear rather than a pointer to something specific, so it was removed; use a BOX outline (optionally with an ARROW pointing at "
                                + "it) instead, which is what an unrecognized kind renders as anyway. There is no REDACT kind either — to hide a secret/token/PII, redact it "
                                + "in the request_text/response_text override (below); a painted black box leaves the original pixels recoverable underneath."),
                        "anchor", Map.of("type", "string", "description", "Targets a named region ICARUS actually drew, instead of guessing pixel coordinates — always prefer this. "
                                + "Works for every kind: BOX frames the region, CROP trims to it, and ARROW is auto-turned into a pointer whose tip lands on the "
                                + "region's edge (tail ~160px out where there's room) — so `{\"kind\":\"ARROW\",\"anchor\":\"response_payload\"}` just works. Guessed x/y for text "
                                + "whose position depends on rendered string width (e.g. a badge after a "
                                + "variable-length label) routinely lands on empty space, since that width isn't knowable from outside the renderer. Available on any "
                                + "server-rendered image (image_base64 omitted), from tightest to loosest — always prefer the tightest one that covers what you're pointing "
                                + "at: \"request_payload\" / \"response_payload\" circles the finding's own injected/reflected value and nothing else (present whenever the "
                                + "finding has a payload — e.g. STRING_SQLI, STRING_XSS, STRING_CMDI — and that exact text was found verbatim in the rendered traffic) — "
                                + "this is the right anchor almost every time an injection finding needs annotating, not a header or a column. Note the payload for a body "
                                + "parameter lives in the REQUEST, not the response — use \"request_payload\" for those (STRING_SQLI etc. inject into the request body; "
                                + "\"response_payload\" only matters for something reflected back, like STRING_XSS). \"request_header:<name>\" / \"response_header:<name>\" "
                                + "circles exactly one header's line (e.g. \"response_header:server\" for a VERSION_DISCLOSURE finding — lowercase the header name); "
                                + "\"request_status_line\" / \"response_status_line\" circles just the request line or HTTP status line (use this for a SERVER_ERROR "
                                + "finding, to point at the 500 itself); \"request_headers\" / \"response_headers\" boxes the whole header block only, still far tighter "
                                + "than a column — the right choice for a MISSING_* header finding, where there's no single line to point at since the header is absent. "
                                + "\"request_column\" / \"response_column\" are the full panes — use these only with CROP, never BOX for pointing at something specific: a "
                                + "box around an entire pane doesn't tell the reader where to look. Boilerplate request/response headers not relevant to the finding (a dozen "
                                + "sec-ch-ua*/Sec-Fetch-*/User-Agent/Cookie lines, etc.) are already collapsed into a single truncation marker by the renderer, so the "
                                + "payload is visible without a CROP for that — CROP is still useful for a large or noisy body. On RATE_LIMIT/NO_RATE_LIMIT findings "
                                + "specifically, also: \"rps\" (the colored requests-per-second badge) and \"blocked\" (the \"← BLOCKED\" marker on the row that tripped "
                                + "the limit, if any). capture_evidence's result echoes back exactly which anchors this particular render had (they vary with what "
                                + "headers/payload were actually present) — check that list rather than guessing names."),
                        "x", Map.of("type", "integer", "description", "Ignored if anchor is set."),
                        "y", Map.of("type", "integer", "description", "Ignored if anchor is set."),
                        "width", Map.of("type", "integer", "description", "For ARROW, the end point's x offset from x. Ignored if anchor is set."),
                        "height", Map.of("type", "integer", "description", "For ARROW, the end point's y offset from y. Ignored if anchor is set.")),
                "required", List.of("kind"));

        Map<String, Object> captureProps = new LinkedHashMap<>();
        captureProps.put("hash", Map.of("type", "string", "description", "The finding's similarityHash, as returned by list_findings"));
        captureProps.put("image_base64", Map.of("type", "string", "description", "Optional base64-encoded screenshot (any format ImageIO reads, e.g. PNG/JPEG) to attach as evidence. "
                + "If omitted, ICARUS renders the evidence image itself from the finding's captured HTTP traffic (or request_text/response_text if given) — "
                + "no screenshot is required."));
        captureProps.put("code", Map.of("type", "string", "description", "Optional block of free text — the output of an external tool you ran to confirm the finding "
                + "(sqlmap, nuclei, ffuf, a curl transcript, a decoded token, a snippet of vulnerable source). ICARUS renders it verbatim into a monospace "
                + "evidence image and attaches it to the finding, so it lands in the report next to the traffic screenshots. Paste the real output; don't "
                + "summarise it. Ignored if image_base64 is given. Use 'title' to label it (defaults to \"External Tool Output\")."));
        captureProps.put("request_text", Map.of("type", "string", "description", "Leave unset unless redaction is actually required — the default (the finding's real captured "
                + "request, with boilerplate headers already collapsed to a truncation marker) is what a report needs. If you must set this, start from "
                + "get_finding/get_evidence's request text and change only what's necessary (e.g. blank out a session token's value in place); keep every "
                + "line exactly as captured otherwise. Never replace the request with a summary — that destroys the evidentiary value of the capture. Note: "
                + "this override text is used exactly as given, with no automatic header collapsing applied to it."));
        captureProps.put("response_text", Map.of("type", "string", "description", "Same rule as request_text: leave unset by default. If set, redact specific values in place only, "
                + "and note it also skips the automatic header collapsing. Never summarize or shorten the response."));
        captureProps.put("title", Map.of("type", "string", "description", "Overrides the rendered evidence banner title (image_base64 omitted). Defaults to the finding's type."));
        captureProps.put("description", Map.of("type", "string", "description", "Overrides the rendered evidence banner description (image_base64 omitted). Defaults to the finding's description."));
        captureProps.put("severity", Map.of("type", "string", "description", "Overrides the rendered evidence banner severity (image_base64 omitted). Defaults to the finding's severity."));
        captureProps.put("force_1080", Map.of("type", "boolean", "description", "Render at 1920x1080 (true, default) or a narrower size (false), when image_base64 is omitted."));
        captureProps.put("caption", Map.of("type", "string", "description", "Evidence caption shown under the image in reports"));
        captureProps.put("annotations", Map.of(
                "type", "array",
                "description", "Optional shapes to draw before saving. Prefer targeting a named \"anchor\" (see capture_evidence's response for the available "
                        + "names) over guessing pixel coordinates — ICARUS knows exactly where it drew the RPS badge or blocked-request marker; you don't. "
                        + "Without an anchor: BOX is a rectangle at (x,y) sized width x height; ARROW runs from (x,y) to (x+width,y+height); CROP "
                        + "truncates the final image to that rectangle and should be listed last.",
                "items", annotationItemSchema));
        var inputSchema = new McpSchema.JsonSchema("object", captureProps,
                List.of("hash"), false, null, null);
        var tool = new McpSchema.Tool("capture_evidence",
                "Capture and annotate ICARUS evidence",
                "Attaches evidence to a finding for the report, optionally drawing boxes/arrows and cropping it first — "
                        + "the headless equivalent of the Evidence Manager's annotation editor. Pass image_base64 to attach a screenshot you already have, or omit it to "
                        + "have ICARUS render the evidence image itself from the finding's real captured traffic — the normal, preferred path, with no screenshot needed. "
                        + "Pass 'code' instead to attach the verbatim output of an external validation tool you ran (sqlmap, nuclei, a curl transcript, vulnerable source) as a monospace image. "
                        + "Do not set request_text/response_text unless you specifically need to redact a value; leave them unset otherwise so the report shows the actual "
                        + "capture, headers included. Call get_evidence first to re-annotate an existing screenshot.",
                inputSchema, null, null, null);

        return new McpServerFeatures.SyncToolSpecification(tool, (exchange, request) -> {
            String hash = (String) request.arguments().get("hash");
            Finding finding = orchestrator.getFindingByHash(hash);
            if (finding == null) {
                return McpSchema.CallToolResult.builder().addTextContent("No finding found for hash: " + hash).isError(true).build();
            }
            String imageBase64 = (String) request.arguments().get("image_base64");
            Object rawCaption = request.arguments().get("caption");
            String caption = rawCaption instanceof String s ? s : "";

            try {
                BufferedImage image;
                Map<String, Rectangle> anchors = new LinkedHashMap<>();
                String code = request.arguments().get("code") instanceof String s && !s.isBlank() ? s : null;
                if (imageBase64 != null && !imageBase64.isBlank()) {
                    byte[] imageBytes = Base64.getDecoder().decode(imageBase64);
                    image = ImageIO.read(new ByteArrayInputStream(imageBytes));
                    if (image == null) {
                        return McpSchema.CallToolResult.builder().addTextContent("image_base64 did not decode to a readable image").isError(true).build();
                    }
                } else if (code != null) {
                    String title = request.arguments().get("title") instanceof String s ? s : null;
                    image = orchestrator.getEvidenceCapture().imageRenderer.renderCodeToImage(code, title);
                } else {
                    image = renderEvidenceImage(finding, request.arguments(), anchors);
                }

                Object rawAnnotations = request.arguments().get("annotations");
                if (rawAnnotations instanceof List<?> list && !list.isEmpty()) {
                    List<icarus.evidence.EvidenceAnnotator.Annotation> annotations = new ArrayList<>();
                    for (Object o : list) {
                        Map<?, ?> m = (Map<?, ?>) o;
                        String kind = (String) m.get("kind");
                        Object anchorName = m.get("anchor");
                        if (anchorName instanceof String name) {
                            Rectangle r = anchors.get(name);
                            if (r == null) {
                                return McpSchema.CallToolResult.builder().addTextContent(
                                        "Unknown anchor '" + name + "'. Available anchors for this render: " + anchors.keySet()
                                                + (anchors.isEmpty() ? " (none — anchors are only available when ICARUS renders the image itself, i.e. image_base64 was omitted)" : "")
                                ).isError(true).build();
                            }
                            if ("ARROW".equals(kind)) {
                                // A box-shaped anchor makes a useless zero-span arrow along its diagonal.
                                // Synthesise a real pointer: tip on the anchor's near edge, tail ~160px out
                                // in whichever horizontal direction has room.
                                int midY = r.y + r.height / 2;
                                boolean fromLeft = r.x > 176;
                                int tipX = fromLeft ? r.x : r.x + r.width;
                                int tailX = fromLeft ? Math.max(8, r.x - 160)
                                                     : Math.min(image.getWidth() - 8, r.x + r.width + 160);
                                annotations.add(new icarus.evidence.EvidenceAnnotator.Annotation(
                                        "ARROW", tailX, midY - 36, tipX - tailX, 36));
                            } else {
                                annotations.add(new icarus.evidence.EvidenceAnnotator.Annotation(kind, r.x, r.y, r.width, r.height));
                            }
                        } else {
                            int x = m.get("x") instanceof Number n ? n.intValue() : 0;
                            int y = m.get("y") instanceof Number n ? n.intValue() : 0;
                            int width = m.get("width") instanceof Number n ? n.intValue() : 0;
                            int height = m.get("height") instanceof Number n ? n.intValue() : 0;
                            annotations.add(new icarus.evidence.EvidenceAnnotator.Annotation(
                                    kind, x, y, width, height));
                        }
                    }
                    image = orchestrator.getEvidenceCapture().annotator.applyAnnotations(image, annotations);
                }

                orchestrator.captureEvidence(finding.withoutMeta("blast_log"), image, caption);
                String anchorNote = anchors.isEmpty() ? "" : " (available anchors were: " + anchors.keySet() + ")";
                return McpSchema.CallToolResult.builder().addTextContent("Evidence captured for " + hash + anchorNote).build();
            } catch (Exception e) {
                return McpSchema.CallToolResult.builder().addTextContent("Failed to capture evidence: " + e.getMessage()).isError(true).build();
            }
        });
    }

    /** Headless counterpart to {@link icarus.evidence.EvidencePhase1Dialog}'s "Apply"/"Annotate" render step —
     *  delegates to {@link icarus.evidence.EvidenceAutoRenderer}, shared with report auto-rendering, so
     *  headless capture and a report's auto-filled image never drift into two different render paths. */
    private BufferedImage renderEvidenceImage(Finding finding, Map<String, Object> args, Map<String, Rectangle> outAnchors) {
        boolean force1080 = !(args.get("force_1080") instanceof Boolean b) || b;
        return icarus.evidence.EvidenceAutoRenderer.render(orchestrator.getEvidenceCapture(), finding,
                args.get("title") instanceof String s1 ? s1 : null,
                args.get("description") instanceof String s2 ? s2 : null,
                args.get("severity") instanceof String s3 ? s3 : null,
                args.get("request_text") instanceof String s4 ? s4 : null,
                args.get("response_text") instanceof String s5 ? s5 : null,
                force1080, outAnchors);
    }

    private McpServerFeatures.SyncToolSpecification listEvidenceTool() {
        var inputSchema = new McpSchema.JsonSchema("object", Map.of(), List.of(), false, null, null);
        var tool = new McpSchema.Tool("list_evidence",
                "List all ICARUS evidence",
                "Lists every captured evidence screenshot across all findings, in report order, with its image_path identifier "
                        + "(used by set_evidence_caption, set_evidence_included, move_evidence, remove_evidence, and reorder_evidence).",
                inputSchema, null, null, null);

        return new McpServerFeatures.SyncToolSpecification(tool, (exchange, request) -> {
            List<Object> items = new ArrayList<>();
            EvidenceCapture ec = orchestrator.getEvidenceCapture();
            for (var ce : ec.getCaptured()) {
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("image_path", ce.imagePath().toString());
                e.put("hash", ce.finding().similarityHash());
                e.put("type", ce.finding().type());
                e.put("caption", ce.caption());
                e.put("included", ec.isIncluded(ce));
                items.add(e);
            }
            return McpSchema.CallToolResult.builder().addTextContent(JsonParser.write(items)).build();
        });
    }

    private McpServerFeatures.SyncToolSpecification setEvidenceCaptionTool() {
        var inputSchema = new McpSchema.JsonSchema("object",
                Map.of(
                        "image_path", Map.of("type", "string", "description", "The evidence's image_path, as returned by list_evidence or get_evidence"),
                        "caption", Map.of("type", "string", "description", "New caption text")),
                List.of("image_path", "caption"), false, null, null);
        var tool = new McpSchema.Tool("set_evidence_caption",
                "Set ICARUS evidence caption",
                "Updates the caption shown under an evidence screenshot in reports.",
                inputSchema, null, null, null);

        return new McpServerFeatures.SyncToolSpecification(tool, (exchange, request) -> {
            String imagePath = (String) request.arguments().get("image_path");
            String caption = (String) request.arguments().get("caption");
            var ce = findEvidence(imagePath);
            if (ce == null) return evidenceNotFound(imagePath);
            orchestrator.getEvidenceCapture().setCaption(ce, caption);
            return McpSchema.CallToolResult.builder().addTextContent("Caption updated.").build();
        });
    }

    private McpServerFeatures.SyncToolSpecification setEvidenceIncludedTool() {
        var inputSchema = new McpSchema.JsonSchema("object",
                Map.of(
                        "image_path", Map.of("type", "string", "description", "The evidence's image_path, as returned by list_evidence or get_evidence"),
                        "included", Map.of("type", "boolean", "description", "Whether this evidence should appear in the next generated report")),
                List.of("image_path", "included"), false, null, null);
        var tool = new McpSchema.Tool("set_evidence_included",
                "Set ICARUS evidence inclusion",
                "Toggles whether an evidence screenshot is included in the next generated report, without deleting it.",
                inputSchema, null, null, null);

        return new McpServerFeatures.SyncToolSpecification(tool, (exchange, request) -> {
            String imagePath = (String) request.arguments().get("image_path");
            boolean included = Boolean.TRUE.equals(request.arguments().get("included"));
            var ce = findEvidence(imagePath);
            if (ce == null) return evidenceNotFound(imagePath);
            orchestrator.getEvidenceCapture().setIncluded(ce, included);
            return McpSchema.CallToolResult.builder().addTextContent("Inclusion updated.").build();
        });
    }

    private McpServerFeatures.SyncToolSpecification moveEvidenceTool() {
        var inputSchema = new McpSchema.JsonSchema("object",
                Map.of(
                        "image_path", Map.of("type", "string", "description", "The evidence's image_path, as returned by list_evidence or get_evidence"),
                        "target_hash", Map.of("type", "string", "description", "similarityHash of the finding to move this evidence to")),
                List.of("image_path", "target_hash"), false, null, null);
        var tool = new McpSchema.Tool("move_evidence",
                "Move ICARUS evidence to another finding",
                "Re-assigns an evidence screenshot to a different finding, moving it between groups in the Evidence Manager and repainting its header banner to match.",
                inputSchema, null, null, null);

        return new McpServerFeatures.SyncToolSpecification(tool, (exchange, request) -> {
            String imagePath = (String) request.arguments().get("image_path");
            String targetHash = (String) request.arguments().get("target_hash");
            var ce = findEvidence(imagePath);
            if (ce == null) return evidenceNotFound(imagePath);
            Finding target = orchestrator.getFindingByHash(targetHash);
            if (target == null) {
                return McpSchema.CallToolResult.builder().addTextContent("No finding found for hash: " + targetHash).isError(true).build();
            }
            orchestrator.getEvidenceCapture().moveToFinding(ce, target);
            return McpSchema.CallToolResult.builder().addTextContent("Moved.").build();
        });
    }

    private McpServerFeatures.SyncToolSpecification removeEvidenceTool() {
        var inputSchema = new McpSchema.JsonSchema("object",
                Map.of("image_path", Map.of("type", "string", "description", "The evidence's image_path, as returned by list_evidence or get_evidence")),
                List.of("image_path"), false, null, null);
        var tool = new McpSchema.Tool("remove_evidence",
                "Remove ICARUS evidence",
                "Permanently deletes a captured evidence screenshot.",
                inputSchema, null, null, null);

        return new McpServerFeatures.SyncToolSpecification(tool, (exchange, request) -> {
            String imagePath = (String) request.arguments().get("image_path");
            var ce = findEvidence(imagePath);
            if (ce == null) return evidenceNotFound(imagePath);
            orchestrator.getEvidenceCapture().removeCaptured(ce);
            return McpSchema.CallToolResult.builder().addTextContent("Removed.").build();
        });
    }

    private McpServerFeatures.SyncToolSpecification reorderEvidenceTool() {
        var inputSchema = new McpSchema.JsonSchema("object",
                Map.of("image_paths", Map.of(
                        "type", "array",
                        "description", "Every evidence item's image_path (from list_evidence), in the desired report order. Must include every current item exactly once.",
                        "items", Map.of("type", "string"))),
                List.of("image_paths"), false, null, null);
        var tool = new McpSchema.Tool("reorder_evidence",
                "Reorder ICARUS evidence",
                "Sets the report order of all captured evidence, same as dragging rows in the Evidence Manager.",
                inputSchema, null, null, null);

        return new McpServerFeatures.SyncToolSpecification(tool, (exchange, request) -> {
            Object raw = request.arguments().get("image_paths");
            if (!(raw instanceof List<?> paths)) {
                return McpSchema.CallToolResult.builder().addTextContent("image_paths is required").isError(true).build();
            }
            EvidenceCapture ec = orchestrator.getEvidenceCapture();
            List<EvidenceCapture.CapturedEvidence> current = ec.getCaptured();
            List<EvidenceCapture.CapturedEvidence> newOrder = new ArrayList<>();
            for (Object p : paths) {
                var ce = findEvidence(String.valueOf(p));
                if (ce == null) return evidenceNotFound(String.valueOf(p));
                newOrder.add(ce);
            }
            if (newOrder.size() != current.size()) {
                return McpSchema.CallToolResult.builder().addTextContent(
                        "image_paths must include every current evidence item exactly once (got " + newOrder.size()
                                + ", expected " + current.size() + ")").isError(true).build();
            }
            ec.reorderCaptured(newOrder);
            return McpSchema.CallToolResult.builder().addTextContent("Reordered.").build();
        });
    }

    private McpServerFeatures.SyncToolSpecification getProjectContextTool() {
        var inputSchema = new McpSchema.JsonSchema("object", Map.of(), List.of(), false, null, null);
        var tool = new McpSchema.Tool("get_project_context",
                "Get auto-detected project metadata",
                "Extracts a project identifier, test type, target scope, environment, and suggested report variables from active Burp state.",
                inputSchema, null, null, null);

        return new McpServerFeatures.SyncToolSpecification(tool, (exchange, request) -> {
            var ctx = ProjectContextDetector.detectContext(api, orchestrator.getAllFindingRecords());
            return McpSchema.CallToolResult.builder().addTextContent(JsonParser.write(ctx)).build();
        });
    }

    private McpServerFeatures.SyncToolSpecification getReportConfigTool() {
        var inputSchema = new McpSchema.JsonSchema("object", Map.of(), List.of(), false, null, null);
        var tool = new McpSchema.Tool("get_report_config",
                "Get ICARUS report config",
                "Returns the current report template: title/author/etc. variables, custom sections, theme colors, TOC settings, and auto-detected project context.",
                inputSchema, null, null, null);

        return new McpServerFeatures.SyncToolSpecification(tool, (exchange, request) ->
                McpSchema.CallToolResult.builder().addTextContent(JsonParser.write(reportConfigToMap(orchestrator.getReportTemplateConfig()))).build());
    }

    private McpServerFeatures.SyncToolSpecification updateReportConfigTool() {
        Map<String, Object> sectionItemSchema = Map.of(
                "type", "object",
                "properties", Map.of(
                        "title", Map.of("type", "string"),
                        "content", Map.of("type", "string", "description", "Markdown content. Standard CommonMark plus GFM pipe tables "
                                + "(| col | col | / |---|---|) — tables render in the HTML report only; in the PDF report a pipe table falls through as "
                                + "plain text, so for PDF-bound reports use bullet lists instead of tables.")),
                "required", List.of("title", "content"));

        Map<String, Object> findingTemplateSchema = Map.of(
                "type", "object",
                "properties", Map.of(
                        "descricao", Map.of("type", "string"),
                        "impacto", Map.of("type", "string"),
                        "recomendacao", Map.of("type", "string"),
                        "severidade", Map.of("type", "string")
                )
        );

        var inputSchema = new McpSchema.JsonSchema("object",
                Map.of(
                        "variables", Map.of("type", "object", "description", "Template variables to set/merge in, e.g. project, date, author, reviewer, environment, report_title"),
                        "sections", Map.of("type", "array", "description", "Full replacement list of custom report sections, in order", "items", sectionItemSchema),
                        "add_section", Map.of("type", "object", "description", "Add a section: {title, content, index}"),
                        "remove_section", Map.of("type", "object", "description", "Remove a section by title: {title}"),
                        "update_section", Map.of("type", "object", "description", "Update an existing section: {title, new_title, new_content}"),
                        "primary_color", Map.of("type", "string", "description", "Hex accent color, e.g. #3e7bb8"),
                        "secondary_color", Map.of("type", "string", "description", "Hex secondary color"),
                        "theme_name", Map.of("type", "string", "description", "light or dark"),
                        "toc_enabled", Map.of("type", "boolean", "description", "Whether the report includes a table of contents"),
                        "findingTemplates", Map.of("type", "object", "description", "Map of finding types to their templates", "additionalProperties", findingTemplateSchema)),
                List.of(), false, null, null);
        var tool = new McpSchema.Tool("update_report_config",
                "Update ICARUS report config",
                "Updates report template settings. Only provided fields are changed. Supports granular section operations (add_section, remove_section, update_section).",
                inputSchema, null, null, null);

        return new McpServerFeatures.SyncToolSpecification(tool, (exchange, request) -> {
            ReportTemplateConfig rtc = orchestrator.getReportTemplateConfig();

            if (request.arguments().get("variables") instanceof Map<?, ?> vars) {
                Map<String, String> merged = new LinkedHashMap<>(rtc.variables());
                vars.forEach((k, v) -> merged.put(String.valueOf(k), String.valueOf(v)));
                rtc.setVariables(merged);
            }

            // Granular section operations
            if (request.arguments().get("add_section") instanceof Map<?, ?> addSec) {
                String title = addSec.get("title") instanceof String s ? s : null;
                String content = addSec.get("content") instanceof String s ? s : "";
                Integer index = addSec.get("index") instanceof Number n ? n.intValue() : null;
                rtc.addSection(title, content, index);
            }
            if (request.arguments().get("remove_section") instanceof Map<?, ?> remSec) {
                String title = remSec.get("title") instanceof String s ? s : null;
                rtc.removeSection(title);
            }
            if (request.arguments().get("update_section") instanceof Map<?, ?> upSec) {
                String title = upSec.get("title") instanceof String s ? s : null;
                String newTitle = upSec.get("new_title") instanceof String s ? s : null;
                String newContent = upSec.get("new_content") instanceof String s ? s : null;
                rtc.updateSection(title, newTitle, newContent);
            }

            if (request.arguments().get("sections") instanceof List<?> sections) {
                List<ReportTemplateConfig.Section> parsed = new ArrayList<>();
                for (Object o : sections) {
                    Map<?, ?> m = (Map<?, ?>) o;
                    parsed.add(new ReportTemplateConfig.Section(String.valueOf(m.get("title")), String.valueOf(m.get("content"))));
                }
                rtc.setSections(parsed);
            }
            if (request.arguments().get("primary_color") instanceof String s) rtc.setPrimaryColor(s);
            if (request.arguments().get("secondary_color") instanceof String s) rtc.setSecondaryColor(s);
            if (request.arguments().get("theme_name") instanceof String s) rtc.setThemeName(s);
            if (request.arguments().get("toc_enabled") instanceof Boolean b) rtc.setTocEnabled(b);

            if (request.arguments().get("findingTemplates") instanceof Map<?, ?> templatesMap) {
                Map<String, ReportTemplateConfig.FindingTemplate> parsedTemplates = new LinkedHashMap<>(rtc.findingTemplates());
                for (Map.Entry<?, ?> entry : templatesMap.entrySet()) {
                    if (entry.getValue() instanceof Map<?, ?> t) {
                        parsedTemplates.put(String.valueOf(entry.getKey()), new ReportTemplateConfig.FindingTemplate(
                                t.get("descricao") instanceof String s ? s : null,
                                t.get("impacto") instanceof String s ? s : null,
                                t.get("recomendacao") instanceof String s ? s : null,
                                t.get("severidade") instanceof String s ? s : null
                        ));
                    }
                }
                rtc.setFindingTemplates(parsedTemplates);
            }

            orchestrator.saveReportTemplateConfig(rtc);
            return McpSchema.CallToolResult.builder().addTextContent(JsonParser.write(reportConfigToMap(rtc))).build();
        });
    }

    private EvidenceCapture.CapturedEvidence findEvidence(String imagePath) {
        for (var ce : orchestrator.getEvidenceCapture().getCaptured()) {
            if (ce.imagePath().toString().equals(imagePath)) return ce;
        }
        return null;
    }

    private static McpSchema.CallToolResult evidenceNotFound(String imagePath) {
        return McpSchema.CallToolResult.builder().addTextContent("No evidence found for image_path: " + imagePath).isError(true).build();
    }

    private Map<String, Object> reportConfigToMap(ReportTemplateConfig rtc) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("variables", rtc.variables());
        List<Object> sections = new ArrayList<>();
        for (var s : rtc.sections()) sections.add(Map.of("title", s.title(), "content", s.content()));
        m.put("sections", sections);
        m.put("primaryColor", rtc.primaryColor());
        m.put("secondaryColor", rtc.secondaryColor());
        m.put("themeName", rtc.themeName());
        m.put("tocEnabled", rtc.tocEnabled());

        Map<String, Object> findingTemplates = new LinkedHashMap<>();
        for (var entry : rtc.findingTemplates().entrySet()) {
            Map<String, String> t = new LinkedHashMap<>();
            t.put("descricao", entry.getValue().descricao());
            t.put("impacto", entry.getValue().impacto());
            t.put("recomendacao", entry.getValue().recomendacao());
            t.put("severidade", entry.getValue().severidade());
            findingTemplates.put(entry.getKey(), t);
        }
        m.put("findingTemplates", findingTemplates);

        var ctx = ProjectContextDetector.detectContext(api, orchestrator.getAllFindingRecords());
        m.put("auto_detected_context", ctx);

        return m;
    }

    private static Map<String, Object> findingToMap(Finding finding, FindingRecord record) {
        return findingToMap(finding, record, false);
    }

    /** Max body bytes rendered per HTTP message in MCP output — enough to reason over,
     *  bounded so a huge JS/HTML body can't blow the agent's context. */
    private static final int MCP_BODY_CAP = 12_288;

    private static Map<String, Object> findingToMap(Finding finding, FindingRecord record, boolean includeTraffic) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("module", finding.module());
        f.put("type", finding.type());
        f.put("description", finding.description());
        f.put("severity", finding.severity().name());
        f.put("category", finding.category().name());
        f.put("path", finding.path());
        f.put("similarityHash", finding.similarityHash());
        f.put("cweIds", finding.cweIds());
        if (!finding.metadata().isEmpty()) f.put("metadata", finding.metadata());
        if (record != null) {
            f.put("count", record.getCount());
            f.put("suppressed", record.isSuppressed());
        }
        if (includeTraffic && finding.evidence() != null) {
            var rr = finding.evidence();
            if (rr.request() != null) f.put("request", renderMessage(
                    rr.request().toString(), rr.request().body() == null ? 0 : rr.request().body().length()));
            if (rr.response() != null) f.put("response", renderMessage(
                    rr.response().toString(), rr.response().body() == null ? 0 : rr.response().body().length()));
        }
        return f;
    }

    /** Full HTTP message text (headers + body), body truncated to {@link #MCP_BODY_CAP} with a
     *  marker. Montoya's {@code toString()} already gives the raw request/response text. */
    private static String renderMessage(String full, int bodyLen) {
        if (full == null) return "";
        if (full.length() <= MCP_BODY_CAP + 4096) return full;
        String kept = full.substring(0, MCP_BODY_CAP + 4096);
        return kept + "\n\n... [truncated by ICARUS MCP — " + full.length() + " chars total, body ~" + bodyLen
                + " bytes; call get_finding_traffic with this hash for the full, untruncated message]";
    }

private McpServerFeatures.SyncToolSpecification addFindingTool() {
        var inputSchema = new McpSchema.JsonSchema("object", Map.of(
                "title", Map.of("type", "string", "description", "Short finding title, e.g. \"Reflected XSS in term parameter\""),
                "description", Map.of("type", "string", "description", "What the vulnerability is and its impact"),
                "severity", Map.of("type", "string", "description", "CRITICAL, HIGH, MEDIUM, LOW, or INFO"),
                "cwe_id", Map.of("type", "string", "description", "Optional CWE identifier, e.g. \"CWE-79\""),
                "raw_request", Map.of("type", "string", "description",
                        "The exact HTTP request that reproduces the finding, verbatim (request line, headers, "
                                + "body). Do NOT summarize it, rewrite it, or add your own comments/annotations "
                                + "into it — paste the real bytes/text you sent. You may trim clearly irrelevant "
                                + "parts (e.g. an unrelated large binary body) if needed, but never rewrite or "
                                + "annotate what you keep."),
                "raw_response", Map.of("type", "string", "description",
                        "The exact HTTP response that proves the finding, verbatim (status line, headers, body). "
                                + "Same rule as raw_request: no summarizing, no rewriting, no inline commentary — "
                                + "trimming clearly irrelevant parts is fine, editorializing is not.")
        ), List.of("title", "description", "severity", "raw_request"), false, null, null);
        var tool = new McpSchema.Tool("add_finding",
                "Add ICARUS finding",
                "Records a vulnerability the LLM confirmed itself (outside any ICARUS module) as a ICARUS "
                        + "finding, so it shows up in list_findings and gets included in generate_icarus_report. "
                        + "raw_request/raw_response are rendered into the report's REQUEST/RESPONSE evidence "
                        + "columns exactly as given — they must be the real request/response text, not a "
                        + "description of it.",
                inputSchema, null, null, null);

        return new McpServerFeatures.SyncToolSpecification(tool, (exchange, request) -> {
            Map<String, Object> args = request.arguments();
            String title = String.valueOf(args.get("title"));
            String description = String.valueOf(args.get("description"));
            String cweId = args.get("cwe_id") instanceof String s && !s.isBlank() ? s : null;
            String rawRequest = args.get("raw_request") instanceof String s ? s : "";
            String rawResponse = args.get("raw_response") instanceof String s ? s : "";

            Severity severity;
            try {
                severity = Severity.valueOf(String.valueOf(args.get("severity")).toUpperCase());
            } catch (IllegalArgumentException e) {
                return McpSchema.CallToolResult.builder()
                        .addTextContent("Invalid severity — use CRITICAL, HIGH, MEDIUM, LOW, or INFO.")
                        .isError(true)
                        .build();
            }

            orchestrator.addFinding(title, description, severity,
                    cweId != null ? List.of(cweId) : List.of(), rawRequest, rawResponse);

            return McpSchema.CallToolResult.builder().addTextContent("Finding added: " + title).build();
        });
    }

    // ── Stage 2: validation, exploitation confirmation, attack-chain correlation ──

    /** Finding types exploit_finding will act on — resending a live payload only makes sense for
     *  ParamValidator's injection detectors, never for a passive header/cookie check. */
    private static final List<String> EXPLOITABLE_TYPES = List.of(
            "STRING_XSS", "STRING_CMDI", "STRING_SSTI", "STRING_SSRF_HEURISTIC", "STRING_SSRF", "STRING_SQLI", "STRING_SQLI_TIME", "IDOR_ADJACENT_ID",
            "Missing Input Validation", "NO_RATE_LIMIT");

    /** Finding types validate_finding can give a real reproduced=true/false for — the exploitable
     *  types above, plus SensitiveHeaderModule's deterministic missing-header/cookie-flag checks
     *  (see {@link icarus.modules.SensitiveHeaderModule#isNowPresent}). Everything else returns the
     *  fresh response for manual review rather than a fabricated verdict. */
    private static final List<String> VALIDATABLE_TYPES = List.of(
            "STRING_XSS", "STRING_CMDI", "STRING_SSTI", "STRING_SSRF_HEURISTIC", "STRING_SSRF", "STRING_SQLI", "STRING_SQLI_TIME", "IDOR_ADJACENT_ID",
            "Missing Input Validation", "NO_RATE_LIMIT",
            "MISSING_HSTS", "MISSING_CSP", "MISSING_XCTO", "MISSING_XFO", "MISSING_RP", "MISSING_PP",
            "COOKIE_MISSING_SECURE", "COOKIE_MISSING_HTTPONLY", "COOKIE_MISSING_SAMESITE",
            "SERVER_ERROR", "VERBOSE_ERROR_LEAK", "VERSION_DISCLOSURE");

    private static boolean isBlockedStatus(int status) {
        return status == 429 || status == 403 || status == 503;
    }

    private record RecheckResult(Boolean reproduced, String note, HttpRequestResponse fresh) {}

    /**
     * Re-sends {@code finding}'s exact captured request live and re-runs the same detection
     * primitive that originally flagged it (shared with {@link ParamValidatorModule} via its
     * public signature constants — not a second hand-copied check that could drift from the
     * original). {@code reproduced} is {@code null} whenever there's no reliable way to answer
     * true/false rather than guessing.
     */
    /**
     * Ensure a captured request has an {@link burp.api.montoya.http.HttpService} so it can be
     * resent. Live requests already carry one; findings saved before the codec persisted
     * host/port come back service-less — rebuild it from the Host header (host:port, secure
     * iff port 443). Returns null when there's nothing to rebuild from.
     */
    private HttpRequest withRebuiltService(HttpRequest req) {
        if (req.httpService() != null) return req;
        String hostHeader = req.headerValue("Host");
        if (hostHeader == null || hostHeader.isBlank()) return null;
        String h = hostHeader.trim();
        int colon = h.lastIndexOf(':');
        String host = colon > 0 ? h.substring(0, colon) : h;
        int port = 80;
        try { if (colon > 0) port = Integer.parseInt(h.substring(colon + 1)); } catch (NumberFormatException ignored) {}
        return req.withService(burp.api.montoya.http.HttpService.httpService(host, port, port == 443));
    }

    private RecheckResult recheckFinding(Finding finding) {
        if (finding.evidence() == null) {
            return new RecheckResult(null, "This finding has no captured request to resend.", null);
        }
        long start = System.currentTimeMillis();
        HttpRequestResponse fresh;
        try {
            HttpRequest req = withRebuiltService(finding.evidence().request());
            if (req == null) {
                return new RecheckResult(null, "This finding has no target binding (saved by an older ICARUS) and no Host header to rebuild one — re-run the scan to refresh it.", null);
            }
            fresh = api.http().sendRequest(req);
        } catch (Exception e) {
            return new RecheckResult(null, "Resend failed: " + e.getMessage(), null);
        }
        long elapsedMs = System.currentTimeMillis() - start;
        if (fresh == null || fresh.response() == null) {
            return new RecheckResult(null, "No response received on resend.", fresh);
        }

        String bodyStr = fresh.response().bodyToString();
        String bodyLower = bodyStr.toLowerCase();
        String payload = finding.metadata().get("payload");

        return switch (finding.type()) {
            case "STRING_XSS" -> {
                boolean hit = payload != null && bodyStr.contains(payload);
                yield new RecheckResult(hit, hit ? "Payload still reflected." : "Payload no longer reflected in the response.", fresh);
            }
            case "STRING_CMDI" -> {
                String match = ParamValidatorModule.firstMatch(bodyLower, ParamValidatorModule.CMDI_SIGNATURES);
                yield new RecheckResult(match != null,
                        match != null ? "Command-output signature still present ('" + match + "')." : "No command-output signature found.", fresh);
            }
            case "STRING_SSTI" -> {
                String evaluated = payload != null ? ParamValidatorModule.SSTI_EXPECTED.get(payload) : null;
                boolean hit = evaluated != null && bodyStr.contains(evaluated);
                yield new RecheckResult(hit,
                        hit ? "Payload still evaluates to '" + evaluated + "'." : "Payload no longer evaluates — may just no longer reflect/execute.", fresh);
            }
            case "STRING_SSRF_HEURISTIC" -> {
                String match = ParamValidatorModule.firstMatch(bodyLower, ParamValidatorModule.SSRF_SIGNATURES);
                yield new RecheckResult(match != null,
                        match != null ? "Metadata/internal-service signature still present ('" + match + "')." : "No such signature found.", fresh);
            }
            case "STRING_SSRF" -> new RecheckResult(null,
                    "This was confirmed via a one-time Burp Collaborator payload — it can't be replayed to reconfirm. Re-run a fresh ParamValidator "
                            + "scan against this endpoint for a new out-of-band confirmation.", fresh);
            case "STRING_SQLI" -> {
                String baselineLengthStr = finding.metadata().get("baselineLength");
                if (baselineLengthStr == null) {
                    yield new RecheckResult(null, "No baseline length was captured with this finding — can't re-diff. Review the fresh response manually.", fresh);
                } else {
                    int baselineLength = Integer.parseInt(baselineLengthStr);
                    int freshLength = fresh.response().body().length();
                    double diffRatio = baselineLength <= 0 ? 0 : Math.abs(freshLength - baselineLength) / (double) baselineLength;
                    boolean hit = diffRatio > 0.20;
                    yield new RecheckResult(hit,
                            hit ? "Still diverges from baseline (" + freshLength + " bytes vs baseline " + baselineLength + " bytes)."
                                : "No longer diverges from baseline (" + freshLength + " bytes vs baseline " + baselineLength + " bytes) — may have been fixed.", fresh);
                }
            }
            case "STRING_SQLI_TIME" -> {
                int threshold = orchestrator.getConfig().getInt("pv.payload_sqli_time_delay_ms", 10000);
                boolean hit = elapsedMs >= threshold;
                yield new RecheckResult(hit,
                        hit ? "Still delays " + elapsedMs + "ms (>= " + threshold + "ms threshold)."
                            : "Only delayed " + elapsedMs + "ms (< " + threshold + "ms threshold) — may no longer be vulnerable.", fresh);
            }
            case "IDOR_ADJACENT_ID" -> {
                int status = fresh.response().statusCode();
                boolean ok = status >= 200 && status <= 299;
                yield new RecheckResult(ok,
                        ok ? "Still returns HTTP " + status + " for the neighboring ID (single-identity check — doesn't confirm data leakage)."
                           : "Now returns HTTP " + status + " — may have been fixed.", fresh);
            }
            case "Missing Input Validation" -> {
                int min = orchestrator.getConfig().getInt("pv.finding_status_min", 200);
                int max = orchestrator.getConfig().getInt("pv.finding_status_max", 299);
                int status = fresh.response().statusCode();
                boolean stillAccepted = status >= min && status <= max;
                yield new RecheckResult(stillAccepted,
                        stillAccepted ? "The flagged mutated request still returns HTTP " + status + " (accepted) — validation gap still present."
                                       : "Now returns HTTP " + status + " — may have been fixed.", fresh);
            }
            case "NO_RATE_LIMIT" -> {
                // Lighter than the original detection's full burst (often 50 requests) — this is
                // meant to be safe to call unattended/repeatedly, not re-run the whole module.
                // A clean pass here doesn't rule out a bypass technique the original scan tried
                // and this quick probe doesn't; it only confirms the plain no-limiting condition.
                int sample = 15;
                int blocked = isBlockedStatus(fresh.response().statusCode()) ? 1 : 0;
                for (int i = 1; i < sample; i++) {
                    try {
                        var r = api.http().sendRequest(finding.evidence().request());
                        if (r != null && r.response() != null && isBlockedStatus(r.response().statusCode())) blocked++;
                    } catch (Exception ignored) {
                        // A failed resend isn't a block signal either way — just doesn't count toward either total.
                    }
                }
                boolean stillNoLimit = blocked == 0;
                int finalBlocked = blocked;
                yield new RecheckResult(stillNoLimit,
                        stillNoLimit ? "Sent " + sample + " requests with no blocking observed — still no rate limiting."
                                     : finalBlocked + "/" + sample + " requests were blocked (429/403/503) — rate limiting now appears to be in place.", fresh);
            }
            case "SERVER_ERROR" -> {
                int freshStatus = fresh.response().statusCode();
                boolean hit = freshStatus >= 500;
                yield new RecheckResult(hit,
                        hit ? "Still returns HTTP " + freshStatus + "." : "Now returns HTTP " + freshStatus + " — may have been fixed.", fresh);
            }
            case "VERBOSE_ERROR_LEAK" -> {
                String verboseMatch = icarus.core.VerboseErrorDetector.getVerboseErrorMatch(bodyStr);
                yield new RecheckResult(verboseMatch != null,
                        verboseMatch != null ? "Still leaking: " + verboseMatch : "No verbose error pattern found on the fresh response.", fresh);
            }
            case "VERSION_DISCLOSURE" -> {
                boolean hit = icarus.modules.SensitiveHeaderModule.hasVersionDisclosure(fresh.response());
                yield new RecheckResult(hit,
                        hit ? "Version-disclosing header still present." : "No version-disclosing header found on the fresh response — may have been fixed.", fresh);
            }
            case "MISSING_HSTS", "MISSING_CSP", "MISSING_XCTO", "MISSING_XFO", "MISSING_RP", "MISSING_PP",
                 "COOKIE_MISSING_SECURE", "COOKIE_MISSING_HTTPONLY", "COOKIE_MISSING_SAMESITE" -> {
                Boolean present = icarus.modules.SensitiveHeaderModule.isNowPresent(finding.type(), fresh.response());
                if (present == null) {
                    yield new RecheckResult(null, "No Set-Cookie header on the fresh response — can't determine this cookie flag's current state.", fresh);
                }
                boolean stillMissing = !present;
                yield new RecheckResult(stillMissing,
                        stillMissing ? "Still missing on the fresh response." : "Now present on the fresh response — this finding appears fixed.", fresh);
            }
            default -> new RecheckResult(null,
                    "No automated re-check exists for finding type '" + finding.type() + "'. The request was resent; review the fresh response manually.", fresh);
        };
    }

    /** Rebuilds {@code finding} with {@code extra} merged into its metadata, keeping every other
     *  field (including module/type/path, so it lands on the same similarityHash and updates the
     *  existing FindingRecord rather than creating a duplicate). */
    private static Finding withMeta(Finding finding, Map<String, String> extra) {
        Finding.Builder builder = Finding.builder(finding.module(), finding.type())
                .description(finding.description())
                .severity(finding.severity())
                .category(finding.category())
                .path(finding.path())
                .evidence(finding.evidence());
        finding.metadata().forEach(builder::meta);
        extra.forEach(builder::meta);
        finding.cweIds().forEach(builder::cwe);
        return builder.build();
    }

    private McpServerFeatures.SyncToolSpecification upsertKbVulnerabilityTool() {
        var inputSchema = new McpSchema.JsonSchema("object",
                Map.of(
                        "name", Map.of("type", "string", "description", "Vulnerability name (primary key)"),
                        "severity", Map.of("type", "string", "description", "Severity: CRITICAL, HIGH, MEDIUM, LOW, or INFO"),
                        "description", Map.of("type", "string", "description", "Vulnerability description"),
                        "impact", Map.of("type", "string", "description", "Vulnerability impact (text)"),
                        "recommendation", Map.of("type", "string", "description", "Remediation recommendation"),
                        "impactLevel", Map.of("type", "string", "description", "Impact level (e.g. ALTO, MEDIO)"),
                        "probLevel", Map.of("type", "string", "description", "Probability level (e.g. ALTO, MEDIO)"),
                        "cwe", Map.of("type", "string", "description", "CWE identifier (e.g. 79)")
                ),
                List.of("name", "severity"), false, null, null);
        var tool = new McpSchema.Tool("upsert_kb_vulnerability",
                "Upsert a vulnerability into the Knowledge Base",
                "Creates or updates a vulnerability in the mutable local overlay. Requires human approval.",
                inputSchema, null, null, null);

        return new McpServerFeatures.SyncToolSpecification(tool, (exchange, request) -> {
            Map<String, Object> args = request.arguments();
            String name = (String) args.get("name");
            String severityStr = (String) args.get("severity");
            Severity severity;
            try {
                severity = Severity.valueOf(severityStr.toUpperCase());
            } catch (Exception e) {
                return McpSchema.CallToolResult.builder().addTextContent("Invalid severity: " + severityStr).isError(true).build();
            }

            icarus.core.KnowledgeBaseEntry entry = new icarus.core.KnowledgeBaseEntry(
                    name, severity.name(),
                    args.get("description") instanceof String s ? s : "",
                    args.get("impact") instanceof String s ? s : "",
                    args.get("recommendation") instanceof String s ? s : "",
                    args.get("impactLevel") instanceof String s ? s : "",
                    args.get("probLevel") instanceof String s ? s : "",
                    args.get("cwe") instanceof String s ? s : "",
                    false
            );

            if (!HumanApprovalGate.requestApproval(api, "Knowledge Base Modification", 
                    "Allow MCP agent to upsert vulnerability:\n\nName: " + name + "\nSeverity: " + severity)) {
                return McpSchema.CallToolResult.builder().addTextContent("Action denied by human operator.").isError(true).build();
            }

            orchestrator.upsertKnowledgeBaseEntry(entry);
            return McpSchema.CallToolResult.builder().addTextContent("Vulnerability '" + name + "' upserted successfully.").build();
        });
    }

    private McpServerFeatures.SyncToolSpecification createFindingFromKbTool() {
        var inputSchema = new McpSchema.JsonSchema("object",
                Map.of("name", Map.of("type", "string", "description", "Name of the vulnerability in the KB")),
                List.of("name"), false, null, null);
        var tool = new McpSchema.Tool("create_finding_from_kb",
                "Create a finding from the Knowledge Base",
                "Looks up a vulnerability by name in the KB and creates a new manual Finding from its details.",
                inputSchema, null, null, null);

        return new McpServerFeatures.SyncToolSpecification(tool, (exchange, request) -> {
            String name = (String) request.arguments().get("name");
            icarus.core.KnowledgeBaseEntry entry = orchestrator.getKnowledgeBaseEntry(name);

            if (entry == null) {
                return McpSchema.CallToolResult.builder().addTextContent("No vulnerability found in KB with name: " + name).isError(true).build();
            }

            Severity severity;
            try {
                severity = Severity.valueOf(entry.severity().toUpperCase());
            } catch (Exception e) {
                severity = Severity.INFO;
            }

            Finding finding = Finding.builder("Manual", entry.name())
                    .description(entry.description())
                    .severity(severity)
                    .category(icarus.core.Category.MANUAL)
                    .path("/")
                    .build();

            orchestrator.updateFinding(finding);
            return McpSchema.CallToolResult.builder().addTextContent("Created finding for '" + name + "'. Hash: " + finding.similarityHash()).build();
        });
    }

    private McpServerFeatures.SyncToolSpecification validateFindingTool() {
        var inputSchema = new McpSchema.JsonSchema("object",
                Map.of("hash", Map.of("type", "string", "description", "The finding's similarityHash, as returned by list_findings")),
                List.of("hash"), false, null, null);
        var tool = new McpSchema.Tool("validate_finding",
                "Re-check a ICARUS finding",
                "Re-sends the finding's exact captured request live and checks whether the same signal that originally flagged it still reproduces. "
                        + "Read-only — no new payloads beyond what's already in the finding's evidence, no approval needed, safe to run unattended (e.g. "
                        + "in a CI/CD pipeline). Only " + VALIDATABLE_TYPES + " get a definite reproduced=true/false; every other finding type returns the "
                        + "fresh response with reproduced=null for manual review, since there's no shared re-check primitive for those modules to reuse "
                        + "rather than a guessed one. Updates the finding's lastValidated metadata either way.",
                inputSchema, null, null, null);

        return new McpServerFeatures.SyncToolSpecification(tool, (exchange, request) -> {
            String hash = (String) request.arguments().get("hash");
            Finding finding = orchestrator.getFindingByHash(hash);
            if (finding == null) {
                return McpSchema.CallToolResult.builder().addTextContent("No finding found for hash: " + hash).isError(true).build();
            }
            RecheckResult result = recheckFinding(finding);

            Map<String, String> extraMeta = new LinkedHashMap<>();
            extraMeta.put("lastValidated", java.time.Instant.now().toString());
            if (result.reproduced() != null) extraMeta.put("lastValidatedResult", result.reproduced() ? "reproduced" : "not_reproduced");
            orchestrator.updateFinding(withMeta(finding, extraMeta));

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("hash", hash);
            out.put("reproduced", result.reproduced());
            out.put("note", result.note());
            if (result.fresh() != null && result.fresh().response() != null) {
                out.put("freshStatus", result.fresh().response().statusCode());
                out.put("freshLength", result.fresh().response().body().length());
                out.put("freshResponse", renderMessage(result.fresh().response().toString(),
                        result.fresh().response().body().length()));
                if (result.fresh().request() != null) {
                    out.put("sentRequest", renderMessage(result.fresh().request().toString(),
                            result.fresh().request().body() == null ? 0 : result.fresh().request().body().length()));
                }
            }
            return McpSchema.CallToolResult.builder().addTextContent(JsonParser.write(out)).build();
        });
    }

    private McpServerFeatures.SyncToolSpecification exploitFindingTool() {
        var inputSchema = new McpSchema.JsonSchema("object",
                Map.of("hash", Map.of("type", "string", "description", "The finding's similarityHash, as returned by list_findings")),
                List.of("hash"), false, null, null);
        var tool = new McpSchema.Tool("exploit_finding",
                "Attempt exploitation confirmation of a ICARUS finding (requires human approval)",
                "Re-sends the finding's exact captured payload live to confirm the same signal still fires. Unlike validate_finding, this ALWAYS blocks on "
                        + "a Swing approval dialog on the analyst's screen inside Burp, showing exactly what request is about to be sent, before sending "
                        + "anything — it does not run unattended, and will not work called from an unattended CI/CD job since there's no one there to "
                        + "click Approve. Only supports " + EXPLOITABLE_TYPES + " (ParamValidator's real detectors — never a passive header/cookie check, "
                        + "use validate_finding for those) — there is no generic multi-vulnerability-"
                        + "class exploitation engine; any other finding type returns a 'not supported' error rather than a fabricated result. "
                        + "STRING_SSRF (Collaborator-confirmed) payloads are single-use and can't be meaningfully replayed.",
                inputSchema, null, null, null);

        return new McpServerFeatures.SyncToolSpecification(tool, (exchange, request) -> {
            String hash = (String) request.arguments().get("hash");
            Finding finding = orchestrator.getFindingByHash(hash);
            if (finding == null) {
                return McpSchema.CallToolResult.builder().addTextContent("No finding found for hash: " + hash).isError(true).build();
            }
            if (!EXPLOITABLE_TYPES.contains(finding.type())) {
                String hint = VALIDATABLE_TYPES.contains(finding.type()) ? " Use validate_finding instead — it can re-check this type." : "";
                return McpSchema.CallToolResult.builder().addTextContent(
                        "exploit_finding does not support finding type '" + finding.type() + "' — no real exploitation-confirmation logic exists for it."
                                + hint + " Supported types: " + EXPLOITABLE_TYPES).isError(true).build();
            }
            if (finding.evidence() == null) {
                return McpSchema.CallToolResult.builder().addTextContent("This finding has no captured request to resend.").isError(true).build();
            }

            var req = finding.evidence().request();
            String payload = finding.metadata().get("payload");
            String details = "ICARUS wants to resend this request to attempt exploitation confirmation:\n\n"
                    + "Finding: " + finding.type() + " on " + finding.path() + "\n"
                    + req.method() + " " + req.path() + "\n"
                    + "Host: " + req.headerValue("Host") + "\n"
                    + (payload != null ? "Payload: " + payload + "\n" : "")
                    + "\nApprove sending this request?";
            if (!HumanApprovalGate.requestApproval(api, "exploit_finding: " + finding.type(), details)) {
                return McpSchema.CallToolResult.builder().addTextContent("Denied by analyst — no request was sent.").isError(true).build();
            }

            RecheckResult result = recheckFinding(finding);

            Map<String, String> extraMeta = new LinkedHashMap<>();
            extraMeta.put("exploitedAt", java.time.Instant.now().toString());
            extraMeta.put("exploitConfirmed", String.valueOf(Boolean.TRUE.equals(result.reproduced())));
            orchestrator.updateFinding(withMeta(finding, extraMeta));

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("hash", hash);
            out.put("exploited", result.reproduced());
            out.put("note", result.note());
            return McpSchema.CallToolResult.builder().addTextContent(JsonParser.write(out)).build();
        });
    }

    /** Rule-based, read-only correlation over findings ICARUS actually produced — no graph
     *  engine, no invented probability scores. A pattern only ever surfaces when every required
     *  finding type is genuinely present on the same path; most of the "classic" chain patterns
     *  from security literature need a detector ICARUS doesn't have (open-redirect, CORS
     *  misconfiguration, MFA absence, admin-endpoint identification) and are deliberately not
     *  listed here rather than defined to silently never match. */
    private record ChainPattern(String name, List<String> requiredTypes, String finalImpact, Severity combinedSeverity, String remediation) {}

    private static final List<ChainPattern> CHAIN_PATTERNS = List.of(
            new ChainPattern("XSS + Missing CSP", List.of("STRING_XSS", "MISSING_CSP"),
                    "Cookie theft / account takeover via reflected XSS with no CSP to contain it", Severity.CRITICAL,
                    "Fix the reflected XSS first (output-encode the parameter); add a Content-Security-Policy as defense-in-depth so a future XSS can't exfiltrate cookies."),
            new ChainPattern("XSS + Weak Cookie (no HttpOnly)", List.of("STRING_XSS", "COOKIE_MISSING_HTTPONLY"),
                    "Session hijacking via XSS reading the session cookie directly", Severity.HIGH,
                    "Fix the reflected XSS first; set HttpOnly on session cookies so script can't read them even if XSS recurs."),
            new ChainPattern("XSS + Weak Cookie (no Secure)", List.of("STRING_XSS", "COOKIE_MISSING_SECURE"),
                    "Session hijacking over unencrypted traffic combined with XSS", Severity.HIGH,
                    "Fix the reflected XSS first; set the Secure flag on session cookies."));

    private List<Map<String, Object>> computeAttackChains(String pathFilter) {
        Map<String, List<FindingRecord>> byPath = new LinkedHashMap<>();
        for (FindingRecord r : orchestrator.getAllFindingRecords()) {
            if (r.isSuppressed()) continue;
            if (pathFilter != null && !r.getFinding().path().equals(pathFilter)) continue;
            byPath.computeIfAbsent(r.getFinding().path(), k -> new ArrayList<>()).add(r);
        }

        List<Map<String, Object>> chains = new ArrayList<>();
        for (var entry : byPath.entrySet()) {
            String path = entry.getKey();
            List<FindingRecord> records = entry.getValue();
            for (ChainPattern pattern : CHAIN_PATTERNS) {
                List<FindingRecord> steps = new ArrayList<>();
                boolean allPresent = true;
                for (String requiredType : pattern.requiredTypes()) {
                    FindingRecord match = records.stream().filter(r -> r.getFinding().type().equals(requiredType)).findFirst().orElse(null);
                    if (match == null) { allPresent = false; break; }
                    steps.add(match);
                }
                if (!allPresent) continue;

                List<Object> stepMaps = new ArrayList<>();
                for (FindingRecord r : steps) {
                    stepMaps.add(Map.of(
                            "hash", r.getFinding().similarityHash(),
                            "type", r.getFinding().type(),
                            "severity", r.getFinding().severity().name(),
                            "exploitable", EXPLOITABLE_TYPES.contains(r.getFinding().type()),
                            "validatable", VALIDATABLE_TYPES.contains(r.getFinding().type())));
                }

                Map<String, Object> chain = new LinkedHashMap<>();
                chain.put("chainId", pattern.name() + "::" + path);
                chain.put("pattern", pattern.name());
                chain.put("path", path);
                chain.put("steps", stepMaps);
                chain.put("finalImpact", pattern.finalImpact());
                chain.put("combinedSeverity", pattern.combinedSeverity().name());
                chain.put("remediation", pattern.remediation());
                chains.add(chain);
            }
        }
        return chains;
    }

    private McpServerFeatures.SyncToolSpecification findAttackChainsTool() {
        var inputSchema = new McpSchema.JsonSchema("object",
                Map.of("path", Map.of("type", "string", "description", "Optional — restrict correlation to findings on this exact path (ICARUS scans one endpoint at a time, so this is usually the endpoint just tested)")),
                List.of(), false, null, null);
        var tool = new McpSchema.Tool("find_attack_chains",
                "Correlate ICARUS findings into known dangerous combinations",
                "Advisory, read-only: looks for pairs of findings on the same path that combine into a worse risk than either alone (e.g. reflected XSS "
                        + "plus a missing Content-Security-Policy). Only fires when every finding a pattern needs was actually produced by ICARUS — no "
                        + "invented data, no execution. Patterns needing a detector ICARUS doesn't have (open redirect, CORS misconfiguration, MFA absence) "
                        + "aren't listed, so they simply never appear rather than silently never matching. Each returned chain has a chainId usable with "
                        + "simulate_attack_chain.",
                inputSchema, null, null, null);

        return new McpServerFeatures.SyncToolSpecification(tool, (exchange, request) -> {
            String pathFilter = request.arguments().get("path") instanceof String s && !s.isBlank() ? s : null;
            return McpSchema.CallToolResult.builder().addTextContent(JsonParser.write(computeAttackChains(pathFilter))).build();
        });
    }

    private McpServerFeatures.SyncToolSpecification simulateAttackChainTool() {
        var inputSchema = new McpSchema.JsonSchema("object",
                Map.of("chain_id", Map.of("type", "string", "description", "A chainId returned by find_attack_chains")),
                List.of("chain_id"), false, null, null);
        var tool = new McpSchema.Tool("simulate_attack_chain",
                "Dry-run an attack chain's execution plan",
                "Re-derives the chain fresh (findings may have changed since find_attack_chains ran) and, if it still matches, returns the ordered list of "
                        + "exploit_finding calls that would be needed to actually work through it — no invented success-probability or risk-score numbers, "
                        + "since nothing in this codebase backs a statistic like that. This tool never sends a request; actual execution means calling "
                        + "exploit_finding yourself for each step's hash, in order, each one gated by its own human-approval dialog.",
                inputSchema, null, null, null);

        return new McpServerFeatures.SyncToolSpecification(tool, (exchange, request) -> {
            String chainId = (String) request.arguments().get("chain_id");
            int sep = chainId.indexOf("::");
            if (sep < 0) {
                return McpSchema.CallToolResult.builder().addTextContent("Malformed chain_id (expected 'Pattern Name::path').").isError(true).build();
            }
            String path = chainId.substring(sep + 2);

            for (Map<String, Object> chain : computeAttackChains(path)) {
                if (!chainId.equals(chain.get("chainId"))) continue;

                @SuppressWarnings("unchecked")
                List<Object> steps = (List<Object>) chain.get("steps");
                List<Object> plan = new ArrayList<>();
                int order = 1;
                for (Object stepObj : steps) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> step = (Map<String, Object>) stepObj;
                    Map<String, Object> planStep = new LinkedHashMap<>(step);
                    planStep.put("order", order++);
                    planStep.put("nextAction", Boolean.TRUE.equals(step.get("exploitable"))
                            ? "call exploit_finding with hash=" + step.get("hash")
                            : Boolean.TRUE.equals(step.get("validatable"))
                                ? "call validate_finding with hash=" + step.get("hash") + " (passive check — not exploitable, but can confirm it's still present)"
                                : "no automated re-check exists for this type — review manually");
                    plan.add(planStep);
                }

                Map<String, Object> out = new LinkedHashMap<>();
                out.put("chainId", chainId);
                out.put("stillValid", true);
                out.put("finalImpact", chain.get("finalImpact"));
                out.put("combinedSeverity", chain.get("combinedSeverity"));
                out.put("executionPlan", plan);
                out.put("note", "Dry-run only — nothing was sent. Call exploit_finding yourself for each step above to actually execute it.");
                return McpSchema.CallToolResult.builder().addTextContent(JsonParser.write(out)).build();
            }

            return McpSchema.CallToolResult.builder().addTextContent(
                    "Chain '" + chainId + "' no longer matches — its findings may have been fixed, suppressed, or re-hashed since find_attack_chains ran. "
                            + "Call find_attack_chains again for the current state.").isError(true).build();
        });
    }
}
