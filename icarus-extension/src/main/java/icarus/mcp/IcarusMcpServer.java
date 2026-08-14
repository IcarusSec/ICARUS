package icarus.mcp;

import burp.api.montoya.MontoyaApi;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import icarus.Icarus;
import icarus.Orchestrator;
import icarus.core.FindingRecord;
import icarus.core.JsonParser;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;

import java.io.IOException;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Local MCP (Model Context Protocol) server embedded in the extension, so AI agents (Claude
 * Desktop and other MCP clients) can query ICARUS findings directly. Runs over legacy SSE —
 * stdio is unavailable since Burp owns the JVM's stdio for its own logging — via
 * {@link IcarusMcpTransportProvider}, a transport hand-written against JDK's own HttpServer:
 * the SDK's ready-made transport is a jakarta.servlet HttpServlet, which would need an embedded
 * servlet container (Jetty, ~8 more jars) just to run one servlet inside a Burp extension whose
 * build has no dependency resolver.
 *
 * <p>Off by default (config key {@code mcp.enabled}, toggled in Settings). Bound to 127.0.0.1
 * only; the API key is derived deterministically from this machine's network hardware addresses
 * (see {@link #machineApiKey()}) rather than randomized per start, so it stays the same across
 * restarts and extension updates — an AI agent's saved config doesn't need re-pasting every time
 * — while still never being written to disk itself, only logged to Burp's output. Loopback-only
 * binding remains the actual security boundary (any local process could derive the same key from
 * the same machine identifiers), so this trades nothing away in practice.
 *
 * <p>JSON mapping uses the SDK's own {@link JacksonMcpJsonMapper} (correct record&lt;-&gt;JSON
 * round-tripping) rather than hand-rolling one against this repo's {@link JsonParser} — but
 * schema validation deliberately does NOT use the SDK's networknt-backed validator (it drags in
 * jackson-dataformat-yaml, snakeyaml, and itu just to validate JSON Schema); see
 * {@link IcarusJsonSchemaValidator}.
 *
 * <p>First shippable slice is intentionally one tool ({@code list_findings}) to prove the
 * custom transport end-to-end before growing the tool surface (trigger_active_scan, finding
 * detail lookup, report generation, etc.).
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

    /** Burp's Output log is the only place the API key is ever shown — never persisted to disk
     *  or held in a Settings field, so this only reports whether/where the server is up. */
    public synchronized String statusSummary() {
        return isRunning()
                ? "Running on http://127.0.0.1:" + port + "/sse — API key logged to Burp's Output panel"
                : "Stopped";
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

            String apiKey = machineApiKey();

            transportProvider = new IcarusMcpTransportProvider(
                    msg -> api.logging().logToError(msg), jsonMapper, "/sse", "/message", apiKey);

            server = McpServer.sync(transportProvider)
                    .serverInfo(new McpSchema.Implementation("icarus", Icarus.VERSION))
                    .jsonMapper(jsonMapper)
                    .jsonSchemaValidator(new IcarusJsonSchemaValidator())
                    .capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
                    .tools(listFindingsTool())
                    .build();

            port = transportProvider.start(requestedPort);
            api.logging().logToOutput("ICARUS MCP server listening on http://127.0.0.1:" + port
                    + "/sse — Authorization: Bearer " + apiKey);
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

    /**
     * Derives a stable API key from this machine's network hardware addresses (sorted so
     * interface enumeration order doesn't matter), so the same machine always gets the same key
     * across restarts and extension updates. Falls back to a random per-run key only if no
     * hardware address is readable at all (e.g. a sandboxed/offline environment).
     */
    private static String machineApiKey() {
        try {
            List<String> macs = new ArrayList<>();
            var interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                byte[] mac = interfaces.nextElement().getHardwareAddress();
                if (mac != null && mac.length > 0) macs.add(HexFormat.of().formatHex(mac));
            }
            Collections.sort(macs);
            String machineIdentity = macs.isEmpty() ? InetAddress.getLocalHost().getHostName() : String.join(",", macs);
            if (machineIdentity.isEmpty()) throw new IllegalStateException("no machine identity available");

            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] digest = sha256.digest(("icarus-mcp:" + machineIdentity).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException | IOException | IllegalStateException e) {
            byte[] keyBytes = new byte[32];
            new SecureRandom().nextBytes(keyBytes);
            return HexFormat.of().formatHex(keyBytes);
        }
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
                var finding = record.getFinding();
                if (severityFilter != null && !finding.severity().name().equalsIgnoreCase(severityFilter)) continue;

                Map<String, Object> f = new LinkedHashMap<>();
                f.put("module", finding.module());
                f.put("type", finding.type());
                f.put("description", finding.description());
                f.put("severity", finding.severity().name());
                f.put("category", finding.category().name());
                f.put("path", finding.path());
                f.put("similarityHash", finding.similarityHash());
                f.put("cweIds", finding.cweIds());
                f.put("count", record.getCount());
                f.put("suppressed", record.isSuppressed());
                findings.add(f);
            }

            return McpSchema.CallToolResult.builder().addTextContent(JsonParser.write(findings)).build();
        });
    }
}
