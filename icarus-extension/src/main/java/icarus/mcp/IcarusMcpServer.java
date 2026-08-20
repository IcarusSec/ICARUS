package icarus.mcp;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import icarus.Icarus;
import icarus.Orchestrator;
import icarus.core.Finding;
import icarus.core.FindingRecord;
import icarus.core.JsonParser;
import icarus.core.ReportTemplateConfig;
import icarus.core.Severity;
import icarus.core.VerboseErrorDetector;
import icarus.evidence.EvidenceAutoRenderer;
import icarus.evidence.EvidenceCapture;
import icarus.evidence.EvidenceAnnotator;
import icarus.modules.ParamValidatorModule;
import icarus.modules.SensitiveHeaderModule;

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
            You're connected to ICARUS, a Burp Suite extension. Every tool here reflects a real \
            capability — there is no generic multi-vulnerability exploitation engine behind this \
            server, so don't assume one exists for a vuln class you don't see a tool/finding type for.

            FINDINGS: list_findings / get_finding / get_reportable_findings / get_passive_findings \
            return what ICARUS's modules already detected. generate_report includes every finding \
            that hasn't been suppressed — validate_finding/exploit_finding are optional extra \
            confirmation, not a prerequisite for a finding to appear in the report. Every included \
            finding gets an evidence image either way, auto-rendered from its captured traffic when \
            nobody captured one manually.

            EVIDENCE: capture_evidence renders its own image from the finding's real captured \
            traffic — omit image_base64 (the normal path) rather than trying to supply a screenshot \
            yourself. Leave request_text/response_text unset unless you specifically need to redact \
            one value in place; never rewrite or summarize the captured traffic, that destroys what \
            the report needs to show. For annotations, prefer "anchor" over guessed x/y/width/height \
            whenever the tool's response lists one available — dynamically positioned text (e.g. a \
            rate-limit RPS badge) has pixel coordinates you cannot predict from outside the renderer. \
            An ARROW pointing at the specific line/value that matters does more for a reader than any \
            box — add one whenever you can name the target coordinates, instead of only outlining or \
            highlighting a whole pane. Never HIGHLIGHT a full request_column/response_column (it just \
            washes the whole pane in translucent yellow and hides the text); reserve HIGHLIGHT for a \
            small anchor or a tight custom rectangle around the few lines that matter. Prefer the \
            tightest available anchor over a full column — request_payload/response_payload circles \
            just the finding's own injected/reflected value (the right choice for almost every \
            injection finding), request_header:<name>/response_header:<name> circles exactly one \
            header line, request_status_line/response_status_line circles just the request/status \
            line, request_headers/response_headers boxes only the header block. A box or highlight \
            is supposed to point at something specific, not outline half the image. When the traffic \
            around the interesting part is cluttered (long cookies, unrelated headers, a huge body), \
            CROP the image down to the relevant region instead of leaving the noise in.

            VALIDATION — read-only, no approval needed, safe to call unattended (e.g. in CI/CD): \
            validate_finding re-sends a finding's captured request and re-checks whether the same \
            signal still fires. Only gives a real true/false for ParamValidator's own detectors \
            (XSS reflection, CMDi/SSTI signatures, SSRF, time-based SQLi, IDOR-adjacent-ID); every \
            other type returns the fresh response for manual review, not a guess. find_attack_chains \
            / simulate_attack_chain correlate findings ICARUS already produced into known-dangerous \
            combinations — advisory only, no execution, no invented probability/risk numbers.

            EXPLOITATION — requires a human at the keyboard, do not call from an unattended pipeline: \
            exploit_finding ALWAYS blocks on a Swing approval dialog inside Burp before sending \
            anything; it will be denied or hang with no one there to click Approve. It only supports \
            the same finding types validate_finding does — for anything else it returns an explicit \
            "not supported" error rather than attempting something it can't back. To act on a chain \
            from simulate_attack_chain, call exploit_finding yourself for each step in order; there \
            is no separate execution tool.

            Never claim a capability this server doesn't expose, and never fabricate a result (a \
            confirmed exploit, a chain probability, a validated finding) these tools didn't produce.""";

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
                    listFindingsTool(), getFindingTool(), suppressFindingTool(), unsuppressFindingTool(),
                    getAuditLogTool(), getPassiveFindingsTool(), clearPassiveFindingsTool(),
                    getReportableFindingsTool(), triggerScanTool(), generateReportTool(),
                    getEvidenceTool(), captureEvidenceTool(),
                    listEvidenceTool(), setEvidenceCaptionTool(), setEvidenceIncludedTool(),
                    moveEvidenceTool(), removeEvidenceTool(), reorderEvidenceTool(),
                    getReportConfigTool(), updateReportConfigTool(),
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
            Object rawFilter = request.arguments().get("severity_filter");
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
                "Looks up one ICARUS finding by its similarityHash, returning its full detail.",
                inputSchema, null, null, null);

        return new McpServerFeatures.SyncToolSpecification(tool, (exchange, request) -> {
            String hash = (String) request.arguments().get("hash");
            Finding finding = orchestrator.getFindingByHash(hash);
            if (finding == null) {
                return McpSchema.CallToolResult.builder().addTextContent("No finding found for hash: " + hash).isError(true).build();
            }
            return McpSchema.CallToolResult.builder().addTextContent(JsonParser.write(findingToMap(finding, null))).build();
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

    private McpServerFeatures.SyncToolSpecification getReportableFindingsTool() {
        var inputSchema = new McpSchema.JsonSchema("object", Map.of(), List.of(), false, null, null);
        var tool = new McpSchema.Tool("get_reportable_findings",
                "Get ICARUS reportable findings",
                "Lists the findings that would be included in a generated report: manually captured/applied evidence, not every passive detection.",
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

    private McpServerFeatures.SyncToolSpecification generateReportTool() {
        var inputSchema = new McpSchema.JsonSchema("object",
                Map.of(
                        "format", Map.of("type", "string", "description", "Report format: html or pdf"),
                        "output_path", Map.of("type", "string", "description", "Absolute filesystem path to write the report to")),
                List.of("format", "output_path"), false, null, null);
        var tool = new McpSchema.Tool("generate_report",
                "Generate ICARUS report",
                "Writes an HTML or PDF report to the given path, overwriting it if it exists. Includes every finding that hasn't been suppressed — "
                        + "validate_finding/exploit_finding are optional extra confirmation, not a prerequisite. Every included finding gets an evidence "
                        + "image either way — auto-rendered from its captured traffic if nobody captured one manually.",
                inputSchema, null, null, null);

        return new McpServerFeatures.SyncToolSpecification(tool, (exchange, request) -> {
            String format = (String) request.arguments().get("format");
            String outputPath = (String) request.arguments().get("output_path");
            try {
                boolean written = orchestrator.generateReport(format, java.nio.file.Path.of(outputPath));
                return written
                        ? McpSchema.CallToolResult.builder().addTextContent("Report written to " + outputPath).build()
                        : McpSchema.CallToolResult.builder().addTextContent("No report was written — either that format is disabled in Settings, or there are no unsuppressed findings to include.").isError(true).build();
            } catch (Exception e) {
                return McpSchema.CallToolResult.builder().addTextContent("Report generation failed: " + e.getMessage()).isError(true).build();
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
                        "kind", Map.of("type", "string", "description", "BOX, ARROW, HIGHLIGHT, REDACT, or CROP"),
                        "anchor", Map.of("type", "string", "description", "Targets a named region ICARUS actually drew, instead of guessing pixel coordinates — prefer this whenever "
                                + "one applies (BOX/HIGHLIGHT/REDACT/CROP only, not ARROW). Guessed x/y for text whose position depends on rendered string width (e.g. a "
                                + "badge after a variable-length label) routinely lands on empty space, since that width isn't knowable from outside the renderer. Available "
                                + "on any server-rendered image (image_base64 omitted), from tightest to loosest — always prefer the tightest one that covers what you're "
                                + "pointing at: \"request_payload\" / \"response_payload\" circles the finding's own injected/reflected value and nothing else (present "
                                + "whenever the finding has a payload — e.g. STRING_SQLI, STRING_XSS, STRING_CMDI — and that exact text was found verbatim in the "
                                + "rendered traffic) — this is the right anchor almost every time an injection finding needs annotating, not a header or a column. "
                                + "\"request_header:<name>\" / \"response_header:<name>\" circles exactly one header's line (e.g. \"response_header:server\" for a "
                                + "VERSION_DISCLOSURE finding — lowercase the header name); \"request_status_line\" / \"response_status_line\" circles just the request "
                                + "line or HTTP status line (use this for a SERVER_ERROR finding, to point at the 500 itself); \"request_headers\" / \"response_headers\" "
                                + "boxes the whole header block only, still far tighter than a column — the right choice for a MISSING_* header finding, where there's no "
                                + "single line to point at since the header is absent. \"request_column\" / \"response_column\" are the full panes — use these only with "
                                + "CROP, never HIGHLIGHT/BOX for pointing at something specific: a box or wash around an entire pane doesn't tell the reader where to look. "
                                + "On RATE_LIMIT/NO_RATE_LIMIT findings specifically, also: \"rps\" (the colored requests-per-second badge) and \"blocked\" (the "
                                + "\"← BLOCKED\" marker on the row that tripped the limit, if any). If a request/response has a lot of irrelevant noise around the part "
                                + "that matters (a long cookie, unrelated headers, a huge body), add a CROP (listed last, after every other annotation) to cut the image "
                                + "down to just the relevant region instead of leaving the clutter in — a tight annotation inside a cluttered image still reads as messy. "
                                + "capture_evidence's result echoes back exactly which anchors this particular render had (they vary with what headers/payload were "
                                + "actually present) — check that list rather than guessing names."),
                        "x", Map.of("type", "integer", "description", "Ignored if anchor is set."),
                        "y", Map.of("type", "integer", "description", "Ignored if anchor is set."),
                        "width", Map.of("type", "integer", "description", "For ARROW, the end point's x offset from x. Ignored if anchor is set."),
                        "height", Map.of("type", "integer", "description", "For ARROW, the end point's y offset from y. Ignored if anchor is set.")),
                "required", List.of("kind"));

        var inputSchema = new McpSchema.JsonSchema("object",
                Map.of(
                        "hash", Map.of("type", "string", "description", "The finding's similarityHash, as returned by list_findings"),
                        "image_base64", Map.of("type", "string", "description", "Optional base64-encoded screenshot (any format ImageIO reads, e.g. PNG/JPEG) to attach as evidence. "
                                + "If omitted, ICARUS renders the evidence image itself from the finding's captured HTTP traffic (or request_text/response_text if given) — "
                                + "no screenshot is required."),
                        "request_text", Map.of("type", "string", "description", "Leave unset unless redaction is actually required — the default (the finding's real captured "
                                + "request) is what a report needs. If you must set this, start from get_finding/get_evidence's request text and change only what's necessary "
                                + "(e.g. blank out a session token's value in place); keep every header, Host, and line exactly as captured. Never omit headers or replace the "
                                + "request with a summary — that destroys the evidentiary value of the capture."),
                        "response_text", Map.of("type", "string", "description", "Same rule as request_text: leave unset by default. If set, redact specific values in place only — "
                                + "keep all response headers (Server, Date, Content-Type, etc.) intact. Never summarize or shorten the response."),
                        "title", Map.of("type", "string", "description", "Overrides the rendered evidence banner title (image_base64 omitted). Defaults to the finding's type."),
                        "description", Map.of("type", "string", "description", "Overrides the rendered evidence banner description (image_base64 omitted). Defaults to the finding's description."),
                        "severity", Map.of("type", "string", "description", "Overrides the rendered evidence banner severity (image_base64 omitted). Defaults to the finding's severity."),
                        "force_1080", Map.of("type", "boolean", "description", "Render at 1920x1080 (true, default) or a narrower size (false), when image_base64 is omitted."),
                        "caption", Map.of("type", "string", "description", "Evidence caption shown under the image in reports"),
                        "annotations", Map.of(
                                "type", "array",
                                "description", "Optional shapes to draw before saving. Prefer targeting a named \"anchor\" (see capture_evidence's response for the available "
                                        + "names) over guessing pixel coordinates — ICARUS knows exactly where it drew the RPS badge or blocked-request marker; you don't. "
                                        + "Without an anchor: BOX/HIGHLIGHT/REDACT are rectangles at (x,y) sized width x height; ARROW runs from (x,y) to (x+width,y+height); "
                                        + "CROP truncates the final image to that rectangle and should be listed last.",
                                "items", annotationItemSchema)),
                List.of("hash"), false, null, null);
        var tool = new McpSchema.Tool("capture_evidence",
                "Capture and annotate ICARUS evidence",
                "Attaches evidence to a finding for the report, optionally drawing boxes/arrows/highlights/redactions and cropping it first — "
                        + "the headless equivalent of the Evidence Manager's annotation editor. Pass image_base64 to attach a screenshot you already have, or omit it to "
                        + "have ICARUS render the evidence image itself from the finding's real captured traffic — the normal, preferred path, with no screenshot needed. "
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
                if (imageBase64 != null && !imageBase64.isBlank()) {
                    byte[] imageBytes = Base64.getDecoder().decode(imageBase64);
                    image = ImageIO.read(new ByteArrayInputStream(imageBytes));
                    if (image == null) {
                        return McpSchema.CallToolResult.builder().addTextContent("image_base64 did not decode to a readable image").isError(true).build();
                    }
                } else {
                    image = renderEvidenceImage(finding, request.arguments(), anchors);
                }

                Object rawAnnotations = request.arguments().get("annotations");
                if (rawAnnotations instanceof List<?> list && !list.isEmpty()) {
                    List<EvidenceAnnotator.Annotation> annotations = new ArrayList<>();
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
                            annotations.add(new EvidenceAnnotator.Annotation(kind, r.x, r.y, r.width, r.height));
                        } else {
                            annotations.add(new EvidenceAnnotator.Annotation(
                                    kind,
                                    ((Number) m.get("x")).intValue(),
                                    ((Number) m.get("y")).intValue(),
                                    ((Number) m.get("width")).intValue(),
                                    ((Number) m.get("height")).intValue()));
                        }
                    }
                    image = EvidenceAnnotator.applyAnnotations(image, annotations);
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
     *  delegates to {@link EvidenceAutoRenderer}, shared with report auto-rendering, so headless capture and
     *  a report's auto-filled image never drift into two different render paths. */
    private BufferedImage renderEvidenceImage(Finding finding, Map<String, Object> args, Map<String, Rectangle> outAnchors) {
        boolean force1080 = !(args.get("force_1080") instanceof Boolean b) || b;
        return EvidenceAutoRenderer.render(api, orchestrator.getConfig(), finding,
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

    private McpServerFeatures.SyncToolSpecification getReportConfigTool() {
        var inputSchema = new McpSchema.JsonSchema("object", Map.of(), List.of(), false, null, null);
        var tool = new McpSchema.Tool("get_report_config",
                "Get ICARUS report config",
                "Returns the current report template: title/author/etc. variables, custom sections, theme colors, and table-of-contents setting.",
                inputSchema, null, null, null);

        return new McpServerFeatures.SyncToolSpecification(tool, (exchange, request) ->
                McpSchema.CallToolResult.builder().addTextContent(JsonParser.write(reportConfigToMap(orchestrator.getReportTemplateConfig()))).build());
    }

    private McpServerFeatures.SyncToolSpecification updateReportConfigTool() {
        Map<String, Object> sectionItemSchema = Map.of(
                "type", "object",
                "properties", Map.of(
                        "title", Map.of("type", "string"),
                        "content", Map.of("type", "string", "description", "Markdown content")),
                "required", List.of("title", "content"));

        var inputSchema = new McpSchema.JsonSchema("object",
                Map.of(
                        "variables", Map.of("type", "object", "description", "Template variables to set/merge in, e.g. projectName, author, revisor, ambient, reportDate"),
                        "sections", Map.of("type", "array", "description", "Full replacement list of custom report sections, in order", "items", sectionItemSchema),
                        "primary_color", Map.of("type", "string", "description", "Hex accent color, e.g. #3e7bb8"),
                        "secondary_color", Map.of("type", "string", "description", "Hex secondary color"),
                        "theme_name", Map.of("type", "string", "description", "light or dark"),
                        "toc_enabled", Map.of("type", "boolean", "description", "Whether the report includes a table of contents")),
                List.of(), false, null, null);
        var tool = new McpSchema.Tool("update_report_config",
                "Update ICARUS report config",
                "Updates report template settings. Only provided fields are changed. variables are merged into the existing set; "
                        + "sections, if provided, fully replaces the current section list.",
                inputSchema, null, null, null);

        return new McpServerFeatures.SyncToolSpecification(tool, (exchange, request) -> {
            ReportTemplateConfig rtc = orchestrator.getReportTemplateConfig();

            if (request.arguments().get("variables") instanceof Map<?, ?> vars) {
                Map<String, String> merged = new LinkedHashMap<>(rtc.variables());
                vars.forEach((k, v) -> merged.put(String.valueOf(k), String.valueOf(v)));
                rtc.setVariables(merged);
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

    private static Map<String, Object> reportConfigToMap(ReportTemplateConfig rtc) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("variables", rtc.variables());
        List<Object> sections = new ArrayList<>();
        for (var s : rtc.sections()) sections.add(Map.of("title", s.title(), "content", s.content()));
        m.put("sections", sections);
        m.put("primaryColor", rtc.primaryColor());
        m.put("secondaryColor", rtc.secondaryColor());
        m.put("themeName", rtc.themeName());
        m.put("tocEnabled", rtc.tocEnabled());
        return m;
    }

    private static Map<String, Object> findingToMap(Finding finding, FindingRecord record) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("module", finding.module());
        f.put("type", finding.type());
        f.put("description", finding.description());
        f.put("severity", finding.severity().name());
        f.put("category", finding.category().name());
        f.put("path", finding.path());
        f.put("similarityHash", finding.similarityHash());
        f.put("cweIds", finding.cweIds());
        if (record != null) {
            f.put("count", record.getCount());
            f.put("suppressed", record.isSuppressed());
        }
        return f;
    }

    // ── Stage 2: validation, exploitation confirmation, attack-chain correlation ──

    /** Finding types exploit_finding will act on — resending a live payload only makes sense for
     *  ParamValidator's injection detectors, never for a passive header/cookie check. */
    private static final List<String> EXPLOITABLE_TYPES = List.of(
            "STRING_XSS", "STRING_CMDI", "STRING_SSTI", "STRING_SSRF_HEURISTIC", "STRING_SSRF", "STRING_SQLI", "STRING_SQLI_TIME", "IDOR_ADJACENT_ID",
            "Missing Input Validation", "NO_RATE_LIMIT");

    /** Finding types validate_finding can give a real reproduced=true/false for — the exploitable
     *  types above, plus SensitiveHeaderModule's deterministic missing-header/cookie-flag checks
     *  (see {@link SensitiveHeaderModule#isNowPresent}). Everything else returns the fresh
     *  response for manual review rather than a fabricated verdict. */
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
    private RecheckResult recheckFinding(Finding finding) {
        if (finding.evidence() == null) {
            return new RecheckResult(null, "This finding has no captured request to resend.", null);
        }
        long start = System.currentTimeMillis();
        HttpRequestResponse fresh;
        try {
            fresh = api.http().sendRequest(finding.evidence().request());
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
                String verboseMatch = VerboseErrorDetector.getVerboseErrorMatch(bodyStr);
                yield new RecheckResult(verboseMatch != null,
                        verboseMatch != null ? "Still leaking: " + verboseMatch : "No verbose error pattern found on the fresh response.", fresh);
            }
            case "VERSION_DISCLOSURE" -> {
                boolean hit = SensitiveHeaderModule.hasVersionDisclosure(fresh.response());
                yield new RecheckResult(hit,
                        hit ? "Version-disclosing header still present." : "No version-disclosing header found on the fresh response — may have been fixed.", fresh);
            }
            case "MISSING_HSTS", "MISSING_CSP", "MISSING_XCTO", "MISSING_XFO", "MISSING_RP", "MISSING_PP",
                 "COOKIE_MISSING_SECURE", "COOKIE_MISSING_HTTPONLY", "COOKIE_MISSING_SAMESITE" -> {
                Boolean present = SensitiveHeaderModule.isNowPresent(finding.type(), fresh.response());
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

    private McpServerFeatures.SyncToolSpecification validateFindingTool() {
        var inputSchema = new McpSchema.JsonSchema("object",
                Map.of("hash", Map.of("type", "string", "description", "The finding's similarityHash, as returned by list_findings")),
                List.of("hash"), false, null, null);
        var tool = new McpSchema.Tool("validate_finding",
                "Re-check an ICARUS finding",
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
            }
            return McpSchema.CallToolResult.builder().addTextContent(JsonParser.write(out)).build();
        });
    }

    private McpServerFeatures.SyncToolSpecification exploitFindingTool() {
        var inputSchema = new McpSchema.JsonSchema("object",
                Map.of("hash", Map.of("type", "string", "description", "The finding's similarityHash, as returned by list_findings")),
                List.of("hash"), false, null, null);
        var tool = new McpSchema.Tool("exploit_finding",
                "Attempt exploitation confirmation of an ICARUS finding (requires human approval)",
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
