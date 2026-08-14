package icarus.mcp;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpServerSession;
import io.modelcontextprotocol.spec.McpServerTransport;
import io.modelcontextprotocol.spec.McpServerTransportProvider;

import reactor.core.publisher.Mono;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Hand-rolled MCP legacy-SSE transport against JDK's built-in {@link HttpServer}, modeled on
 * mcp-core's own {@code HttpServletSseServerTransportProvider} for matching wire behavior
 * (same endpoint/event shape) without needing a servlet container (Jetty) just to run one
 * servlet inside a Burp extension.
 *
 * <p>GET {@code ssePath} opens an SSE stream, mints a session, and emits an {@code endpoint}
 * event carrying the POST URL for that session; POST {@code messagePath}{@code ?sessionId=...}
 * accepts one JSON-RPC message per request and hands it to that session (returning 202
 * immediately) — the actual JSON-RPC response/notifications flow back asynchronously over the
 * still-open SSE stream via {@link SseSessionTransport#sendMessage}.
 */
final class IcarusMcpTransportProvider implements McpServerTransportProvider {

    private final Consumer<String> errorLog;
    private final McpJsonMapper jsonMapper;
    private final String ssePath;
    private final String messagePath;
    private final String apiKey;

    private final Map<String, McpServerSession> sessions = new ConcurrentHashMap<>();
    private volatile McpServerSession.Factory sessionFactory;
    private HttpServer httpServer;

    IcarusMcpTransportProvider(Consumer<String> errorLog, McpJsonMapper jsonMapper, String ssePath, String messagePath, String apiKey) {
        this.errorLog = errorLog;
        this.jsonMapper = jsonMapper;
        this.ssePath = ssePath;
        this.messagePath = messagePath;
        this.apiKey = apiKey;
    }

    /** Binds and starts the HTTP server on 127.0.0.1. {@code port} 0 picks an ephemeral port. Returns the bound port. */
    int start(int port) throws IOException {
        httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        // GET /sse blocks its handler thread for the lifetime of the session (see
        // SseSessionTransport#awaitClose) — a single-threaded default executor would let one
        // open SSE connection starve every other request, including the POSTs that session
        // needs to receive. Daemon threads so a stuck connection can't block extensionUnloaded.
        httpServer.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "icarus-mcp");
            t.setDaemon(true);
            return t;
        }));
        httpServer.createContext(ssePath, this::handleSse);
        httpServer.createContext(messagePath, this::handleMessage);
        httpServer.start();
        return httpServer.getAddress().getPort();
    }

    void stop() {
        closeGracefully().block();
        if (httpServer != null) httpServer.stop(0);
    }

    @Override
    public void setSessionFactory(McpServerSession.Factory sessionFactory) {
        this.sessionFactory = sessionFactory;
    }

    @Override
    public Mono<Void> notifyClients(String method, Object params) {
        for (McpServerSession session : sessions.values()) {
            session.sendNotification(method, params)
                    .subscribe(v -> {}, e -> errorLog.accept("ICARUS MCP notify failed: " + e));
        }
        return Mono.empty();
    }

    @Override
    public Mono<Void> closeGracefully() {
        return Mono.fromRunnable(() -> {
            sessions.values().forEach(McpServerSession::close);
            sessions.clear();
        });
    }

    private boolean authorized(HttpExchange exchange) {
        String header = exchange.getRequestHeaders().getFirst("Authorization");
        return header != null && header.equals("Bearer " + apiKey);
    }

    private void handleSse(HttpExchange exchange) throws IOException {
        String sessionId = null;
        try {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            if (!authorized(exchange)) {
                exchange.sendResponseHeaders(401, -1);
                return;
            }

            sessionId = UUID.randomUUID().toString();
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.getResponseHeaders().add("Cache-Control", "no-cache");
            exchange.sendResponseHeaders(200, 0); // 0 = chunked, unknown length: keep streaming

            SseSessionTransport transport = new SseSessionTransport(sessionId, exchange);
            sessions.put(sessionId, sessionFactory.create(transport));
            transport.writeEvent("endpoint", messagePath + "?sessionId=" + sessionId);

            // Blocks this pooled thread for the session's lifetime — see start()'s comment on
            // why the executor must support many concurrent threads.
            transport.awaitClose();
        } finally {
            if (sessionId != null) sessions.remove(sessionId);
            exchange.close();
        }
    }

    private void handleMessage(HttpExchange exchange) throws IOException {
        try {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            if (!authorized(exchange)) {
                exchange.sendResponseHeaders(401, -1);
                return;
            }

            String sessionId = queryParam(exchange.getRequestURI().getRawQuery(), "sessionId");
            McpServerSession session = sessionId != null ? sessions.get(sessionId) : null;
            if (session == null) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }

            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            McpSchema.JSONRPCMessage message = McpSchema.deserializeJsonRpcMessage(jsonMapper, body);
            session.handle(message).block();

            exchange.sendResponseHeaders(202, -1);
        } catch (Exception e) {
            errorLog.accept("ICARUS MCP message handling failed: " + e);
            exchange.sendResponseHeaders(500, -1);
        } finally {
            exchange.close();
        }
    }

    private static String queryParam(String rawQuery, String name) {
        if (rawQuery == null) return null;
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            if (eq >= 0 && pair.substring(0, eq).equals(name)) return pair.substring(eq + 1);
        }
        return null;
    }

    /** Per-connection transport: one open SSE stream, bound to one {@link McpServerSession}. */
    private final class SseSessionTransport implements McpServerTransport {
        private final String sessionId;
        private final OutputStream out;
        private final Object writeLock = new Object();
        private final CountDownLatch closeLatch = new CountDownLatch(1);
        private volatile boolean closed = false;

        SseSessionTransport(String sessionId, HttpExchange exchange) {
            this.sessionId = sessionId;
            this.out = exchange.getResponseBody();
        }

        void writeEvent(String event, String data) throws IOException {
            synchronized (writeLock) {
                out.write(("event: " + event + "\ndata: " + data + "\n\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
        }

        /** ponytail: no idle keep-alive ping — this is a local, single-user loopback server, so
         *  a dead connection is only noticed on the next write attempt. Add a periodic ping if
         *  intermediary timeouts or multi-client use ever make that noticeable. */
        void awaitClose() {
            try {
                closeLatch.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        private void markClosed() {
            if (closed) return;
            closed = true;
            sessions.remove(sessionId);
            closeLatch.countDown();
        }

        @Override
        public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
            return Mono.fromRunnable(() -> {
                try {
                    writeEvent("message", jsonMapper.writeValueAsString(message));
                } catch (IOException e) {
                    markClosed();
                }
            });
        }

        @Override
        public <T> T unmarshalFrom(Object data, TypeRef<T> typeRef) {
            return jsonMapper.convertValue(data, typeRef);
        }

        @Override
        public Mono<Void> closeGracefully() {
            return Mono.fromRunnable(this::markClosed);
        }

        @Override
        public void close() {
            markClosed();
        }
    }

    /** `java -cp build_manual/libs/icarus-<version>.jar icarus.mcp.IcarusMcpTransportProvider`
     *  — end-to-end self-check: real HTTP+SSE client against a real McpSyncServer built on this
     *  transport, driving initialize -> notifications/initialized -> tools/call over the wire. */
    public static void main(String[] args) throws Exception {
        var jsonMapper = new io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper(new com.fasterxml.jackson.databind.ObjectMapper());
        String apiKey = "test-key";
        var provider = new IcarusMcpTransportProvider(System.err::println, jsonMapper, "/sse", "/message", apiKey);

        var tool = new McpSchema.Tool("echo", "Echo", "Echoes back the given text",
                new McpSchema.JsonSchema("object", Map.of("text", Map.of("type", "string")), List.of("text"), false, null, null),
                null, null, null);
        var toolSpec = new io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification(tool,
                (exchange, request) -> McpSchema.CallToolResult.builder()
                        .addTextContent("echo:" + request.arguments().get("text"))
                        .build());

        var server = io.modelcontextprotocol.server.McpServer.sync(provider)
                .serverInfo(new McpSchema.Implementation("icarus-test", "0.0.1"))
                .jsonMapper(jsonMapper)
                .jsonSchemaValidator(new IcarusJsonSchemaValidator())
                .capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
                .tools(toolSpec)
                .build();

        int port = provider.start(0);
        try {
            var events = new java.util.concurrent.LinkedBlockingQueue<String>();
            var client = java.net.http.HttpClient.newHttpClient();
            var sseRequest = java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:" + port + "/sse"))
                    .header("Authorization", "Bearer " + apiKey).GET().build();

            Thread sseThread = new Thread(() -> {
                try {
                    var response = client.send(sseRequest, java.net.http.HttpResponse.BodyHandlers.ofLines());
                    String pendingEvent = null;
                    var it = response.body().iterator();
                    while (it.hasNext()) {
                        String line = it.next();
                        if (line.startsWith("event: ")) pendingEvent = line.substring(7);
                        else if (line.startsWith("data: ")) events.offer(pendingEvent + "|" + line.substring(6));
                    }
                } catch (Exception ignored) {
                    // connection closed at test teardown
                }
            }, "sse-test-reader");
            sseThread.setDaemon(true);
            sseThread.start();

            String endpointEvent = events.poll(5, java.util.concurrent.TimeUnit.SECONDS);
            assert endpointEvent != null && endpointEvent.startsWith("endpoint|") : "did not receive endpoint event";
            String messageUrl = "http://127.0.0.1:" + port + endpointEvent.substring("endpoint|".length());

            postJson(client, messageUrl, apiKey,
                    "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2024-11-05\",\"capabilities\":{},\"clientInfo\":{\"name\":\"test\",\"version\":\"0\"}}}");
            String initResponse = events.poll(5, java.util.concurrent.TimeUnit.SECONDS);
            assert initResponse != null && initResponse.startsWith("message|") : "no initialize response";

            postJson(client, messageUrl, apiKey, "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");

            postJson(client, messageUrl, apiKey,
                    "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"echo\",\"arguments\":{\"text\":\"hi\"}}}");
            String callResponse = events.poll(5, java.util.concurrent.TimeUnit.SECONDS);
            assert callResponse != null && callResponse.contains("echo:hi") : "tools/call did not echo back: " + callResponse;

            System.out.println("IcarusMcpTransportProvider self-check passed (run with -ea to enforce).");
        } finally {
            server.closeGracefully();
            provider.stop();
        }
    }

    private static void postJson(java.net.http.HttpClient client, String url, String apiKey, String body) throws Exception {
        var request = java.net.http.HttpRequest.newBuilder(java.net.URI.create(url))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body))
                .build();
        var response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 202) {
            throw new RuntimeException("POST " + url + " failed: " + response.statusCode() + " " + response.body());
        }
    }
}
