/**
 * Embeds the official Java MCP SDK ({@code io.modelcontextprotocol.sdk:mcp-core}) directly in
 * the extension so AI agents can query ICARUS findings over a local MCP connection — see
 * {@link icarus.mcp.IcarusMcpServer} for the full rationale and {@link
 * icarus.mcp.IcarusMcpTransportProvider} for the hand-rolled SSE transport.
 *
 * <p>Deliberately excluded from the bundled dependency set: the SDK's own
 * {@code HttpServletSseServerTransportProvider} (needs a servlet container — Jetty — which is
 * a lot of extra jars for a manual javac/jar build with no dependency resolver) and
 * {@code com.networknt:json-schema-validator} (drags in jackson-dataformat-yaml, snakeyaml,
 * and itu just to validate JSON Schema). {@code mcp-json-jackson2}'s {@code
 * DefaultJsonSchemaValidator} class — which depends on that validator — still ships inside the
 * bundled jar unused; the JVM never loads it because {@link icarus.mcp.IcarusMcpServer} wires
 * {@link icarus.mcp.IcarusJsonSchemaValidator} explicitly instead of going through the SDK's
 * ServiceLoader-based discovery (which the build's flat, unshaded, META-INF-stripped fat jar
 * can't rely on anyway).
 */
package icarus.mcp;
