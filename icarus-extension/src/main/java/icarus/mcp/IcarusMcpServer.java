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
import icarus.evidence.EvidenceCapture;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;

import javax.imageio.ImageIO;
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

            server = McpServer.sync(transportProvider)
                    .serverInfo(new McpSchema.Implementation("icarus", Icarus.VERSION))
                    .jsonMapper(jsonMapper)
                    .jsonSchemaValidator(new IcarusJsonSchemaValidator())
                    .capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
                    .tools(listFindingsTool(), getFindingTool(), suppressFindingTool(), unsuppressFindingTool(),
                            getAuditLogTool(), getPassiveFindingsTool(), clearPassiveFindingsTool(),
                            getReportableFindingsTool(), triggerScanTool(), generateReportTool(),
                            getEvidenceTool(), captureEvidenceTool())
                    .build();

            port = transportProvider.start(requestedPort);
            api.logging().logToOutput("ICARUS MCP server listening on http://127.0.0.1:" + port + "/mcp");
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
                "Writes an HTML or PDF report of every non-suppressed ICARUS finding to the given path, overwriting it if it exists. "
                        + "Suppress false positives first with suppress_finding so they're excluded.",
                inputSchema, null, null, null);

        return new McpServerFeatures.SyncToolSpecification(tool, (exchange, request) -> {
            String format = (String) request.arguments().get("format");
            String outputPath = (String) request.arguments().get("output_path");
            try {
                boolean written = orchestrator.generateReport(format, java.nio.file.Path.of(outputPath));
                return written
                        ? McpSchema.CallToolResult.builder().addTextContent("Report written to " + outputPath).build()
                        : McpSchema.CallToolResult.builder().addTextContent("No report was written — nothing to report, or that format is disabled in Settings.").isError(true).build();
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
                    e.put("caption", ce.caption());
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
                        "x", Map.of("type", "integer"),
                        "y", Map.of("type", "integer"),
                        "width", Map.of("type", "integer", "description", "For ARROW, the end point's x offset from x"),
                        "height", Map.of("type", "integer", "description", "For ARROW, the end point's y offset from y")),
                "required", List.of("kind", "x", "y", "width", "height"));

        var inputSchema = new McpSchema.JsonSchema("object",
                Map.of(
                        "hash", Map.of("type", "string", "description", "The finding's similarityHash, as returned by list_findings"),
                        "image_base64", Map.of("type", "string", "description", "Base64-encoded screenshot (any format ImageIO reads, e.g. PNG/JPEG) to attach as evidence"),
                        "caption", Map.of("type", "string", "description", "Evidence caption shown under the image in reports"),
                        "annotations", Map.of(
                                "type", "array",
                                "description", "Optional shapes to draw before saving, in image pixel coordinates. BOX/HIGHLIGHT/REDACT are rectangles at (x,y) sized width x height; "
                                        + "ARROW runs from (x,y) to (x+width,y+height); CROP truncates the final image to that rectangle and should be listed last.",
                                "items", annotationItemSchema)),
                List.of("hash", "image_base64"), false, null, null);
        var tool = new McpSchema.Tool("capture_evidence",
                "Capture and annotate ICARUS evidence",
                "Attaches a screenshot to a finding as reportable evidence, optionally drawing boxes/arrows/highlights/redactions and cropping it first — "
                        + "the headless equivalent of the Evidence Manager's annotation editor. Call get_evidence first to re-annotate an existing screenshot.",
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
                byte[] imageBytes = Base64.getDecoder().decode(imageBase64);
                BufferedImage image = ImageIO.read(new ByteArrayInputStream(imageBytes));
                if (image == null) {
                    return McpSchema.CallToolResult.builder().addTextContent("image_base64 did not decode to a readable image").isError(true).build();
                }

                Object rawAnnotations = request.arguments().get("annotations");
                if (rawAnnotations instanceof List<?> list && !list.isEmpty()) {
                    List<EvidenceCapture.Annotation> annotations = new ArrayList<>();
                    for (Object o : list) {
                        Map<?, ?> m = (Map<?, ?>) o;
                        annotations.add(new EvidenceCapture.Annotation(
                                (String) m.get("kind"),
                                ((Number) m.get("x")).intValue(),
                                ((Number) m.get("y")).intValue(),
                                ((Number) m.get("width")).intValue(),
                                ((Number) m.get("height")).intValue()));
                    }
                    image = orchestrator.getEvidenceCapture().applyAnnotations(image, annotations);
                }

                orchestrator.captureEvidence(finding, image, caption);
                return McpSchema.CallToolResult.builder().addTextContent("Evidence captured for " + hash).build();
            } catch (Exception e) {
                return McpSchema.CallToolResult.builder().addTextContent("Failed to capture evidence: " + e.getMessage()).isError(true).build();
            }
        });
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
}
