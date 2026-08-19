package icarus.mcp;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpStreamableServerSession;
import io.modelcontextprotocol.spec.McpStreamableServerTransport;
import io.modelcontextprotocol.spec.McpStreamableServerTransportProvider;

import reactor.core.publisher.Mono;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Hand-rolled MCP Streamable HTTP transport (spec revision 2025-03-26) against a raw
 * {@link ServerSocket}, single {@code /mcp} endpoint. Every JSON-RPC request is answered
 * synchronously as a plain {@code application/json} POST response — no server-initiated
 * requests are needed by this extension's tool surface, so the optional SSE-stream response
 * mode and the standalone GET listening stream are not implemented.
 *
 * <p>Deliberately does NOT use JDK's built-in {@code com.sun.net.httpserver.HttpServer}: that
 * class lives in the {@code jdk.httpserver} platform module, which Burp's extension classloader
 * does not resolve (extensions are loaded via a plain {@code URLClassLoader} whose delegation
 * doesn't reach it), producing {@code ClassNotFoundException} at runtime despite compiling fine.
 * A raw socket server needs only {@code java.base}, which is always resolvable. Same reasoning
 * rules out the SDK's own {@code HttpServletStreamableServerTransportProvider}: it's a
 * {@code jakarta.servlet.HttpServlet}, which would need an embedded servlet container (Jetty)
 * just to run one servlet inside a Burp extension.
 *
 * <p>Every connection is handled as exactly one request/response with {@code Connection: close}
 * — no keep-alive, no chunked encoding — since this is a local, low-volume, single-user loopback
 * server.
 */
final class IcarusMcpTransportProvider implements McpStreamableServerTransportProvider {

    private final Consumer<String> errorLog;
    private final McpJsonMapper jsonMapper;
    private final String mcpPath;

    private final Map<String, McpStreamableServerSession> sessions = new ConcurrentHashMap<>();
    private volatile McpStreamableServerSession.Factory sessionFactory;
    private ServerSocket serverSocket;
    private ExecutorService executor;
    private volatile boolean running;

    IcarusMcpTransportProvider(Consumer<String> errorLog, McpJsonMapper jsonMapper, String mcpPath) {
        this.errorLog = errorLog;
        this.jsonMapper = jsonMapper;
        this.mcpPath = mcpPath;
    }

    /** Binds and starts the HTTP server on 127.0.0.1. {@code port} 0 picks an ephemeral port. Returns the bound port. */
    int start(int port) throws IOException {
        serverSocket = new ServerSocket();
        serverSocket.bind(new InetSocketAddress("127.0.0.1", port));
        running = true;
        executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "icarus-mcp");
            t.setDaemon(true);
            return t;
        });
        Thread acceptThread = new Thread(this::acceptLoop, "icarus-mcp-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
        return serverSocket.getLocalPort();
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = serverSocket.accept();
                executor.submit(() -> handleConnection(socket));
            } catch (IOException e) {
                if (running) errorLog.accept("ICARUS MCP accept failed: " + e);
            }
        }
    }

    void stop() {
        running = false;
        closeGracefully().block();
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (IOException ignored) {
            // already tearing down
        }
        if (executor != null) executor.shutdownNow();
    }

    @Override
    public void setSessionFactory(McpStreamableServerSession.Factory sessionFactory) {
        this.sessionFactory = sessionFactory;
    }

    @Override
    public Mono<Void> notifyClients(String method, Object params) {
        for (McpStreamableServerSession session : sessions.values()) {
            session.sendNotification(method, params)
                    .subscribe(v -> {}, e -> errorLog.accept("ICARUS MCP notify failed: " + e));
        }
        return Mono.empty();
    }

    @Override
    public Mono<Void> closeGracefully() {
        return Mono.fromRunnable(() -> {
            sessions.values().forEach(McpStreamableServerSession::close);
            sessions.clear();
        });
    }

    private void handleConnection(Socket socket) {
        try {
            InputStream in = socket.getInputStream();
            String requestLine = readLine(in);
            if (requestLine == null || requestLine.isEmpty()) {
                socket.close();
                return;
            }
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) {
                socket.close();
                return;
            }
            String method = parts[0];
            String path = parts[1];
            int q = path.indexOf('?');
            if (q >= 0) path = path.substring(0, q);

            Map<String, String> headers = new HashMap<>();
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                int idx = line.indexOf(':');
                if (idx > 0) headers.put(line.substring(0, idx).trim().toLowerCase(), line.substring(idx + 1).trim());
            }

            if (!mcpPath.equals(path)) {
                sendSimpleResponse(socket, 404);
                socket.close();
                return;
            }

            if ("POST".equalsIgnoreCase(method)) {
                int contentLength = 0;
                try {
                    contentLength = Integer.parseInt(headers.getOrDefault("content-length", "0"));
                } catch (NumberFormatException ignored) {
                    // treat as empty body
                }
                byte[] body = in.readNBytes(contentLength);
                handlePost(socket, headers.get("mcp-session-id"), body);
            } else {
                // GET (standalone listening stream) and DELETE (session termination) are legal
                // per spec to omit; this extension has no server-initiated requests to push.
                sendSimpleResponse(socket, 405);
                socket.close();
            }
        } catch (IOException e) {
            errorLog.accept("ICARUS MCP request handling failed: " + e);
            try {
                socket.close();
            } catch (IOException ignored) {
                // already broken
            }
        }
    }

    private void handlePost(Socket socket, String sessionId, byte[] body) {
        try {
            McpSchema.JSONRPCMessage message = McpSchema.deserializeJsonRpcMessage(jsonMapper, new String(body, StandardCharsets.UTF_8));

            if (message instanceof McpSchema.JSONRPCRequest request) {
                if ("initialize".equals(request.method())) {
                    handleInitialize(socket, request);
                } else {
                    handleRequest(socket, sessionId, request);
                }
            } else if (message instanceof McpSchema.JSONRPCNotification notification) {
                McpStreamableServerSession session = sessions.get(sessionId);
                if (session == null) {
                    sendSimpleResponse(socket, 404);
                    return;
                }
                session.accept(notification).block();
                sendSimpleResponse(socket, 202);
            } else if (message instanceof McpSchema.JSONRPCResponse response) {
                McpStreamableServerSession session = sessions.get(sessionId);
                if (session == null) {
                    sendSimpleResponse(socket, 404);
                    return;
                }
                session.accept(response).block();
                sendSimpleResponse(socket, 202);
            } else {
                sendSimpleResponse(socket, 400);
            }
        } catch (Exception e) {
            errorLog.accept("ICARUS MCP message handling failed: " + e);
            try {
                sendSimpleResponse(socket, 500);
            } catch (IOException ignored) {
                // already broken
            }
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {
                // already closed/broken
            }
        }
    }

    private void handleInitialize(Socket socket, McpSchema.JSONRPCRequest request) throws IOException {
        McpSchema.InitializeRequest initRequest = jsonMapper.convertValue(request.params(), McpSchema.InitializeRequest.class);
        McpStreamableServerSession.McpStreamableServerSessionInit init = sessionFactory.startSession(initRequest);
        McpSchema.InitializeResult result = init.initResult().block();

        String sessionId = UUID.randomUUID().toString();
        sessions.put(sessionId, init.session());

        McpSchema.JSONRPCResponse response = new McpSchema.JSONRPCResponse(McpSchema.JSONRPC_VERSION, request.id(), result, null);
        sendJson(socket, 200, jsonMapper.writeValueAsString(response), sessionId);
    }

    private void handleRequest(Socket socket, String sessionId, McpSchema.JSONRPCRequest request) throws Exception {
        McpStreamableServerSession session = sessionId != null ? sessions.get(sessionId) : null;
        if (session == null) {
            sendSimpleResponse(socket, 404);
            return;
        }

        CompletableFuture<McpSchema.JSONRPCMessage> captured = new CompletableFuture<>();
        McpStreamableServerTransport captureTransport = new McpStreamableServerTransport() {
            @Override
            public Mono<Void> sendMessage(McpSchema.JSONRPCMessage msg, String eventId) {
                return Mono.fromRunnable(() -> captured.complete(msg));
            }

            @Override
            public Mono<Void> sendMessage(McpSchema.JSONRPCMessage msg) {
                return sendMessage(msg, null);
            }

            @Override
            public <T> T unmarshalFrom(Object data, TypeRef<T> typeRef) {
                return jsonMapper.convertValue(data, typeRef);
            }

            @Override
            public Mono<Void> closeGracefully() {
                return Mono.empty();
            }

            @Override
            public void close() {
                // nothing to release: this transport only ever captures one response
            }
        };

        session.responseStream(request, captureTransport).block();
        McpSchema.JSONRPCMessage response = captured.get(30, TimeUnit.SECONDS);
        sendJson(socket, 200, jsonMapper.writeValueAsString(response), null);
    }

    private static void sendJson(Socket socket, int status, String json, String sessionId) throws IOException {
        byte[] bodyBytes = json.getBytes(StandardCharsets.UTF_8);
        StringBuilder response = new StringBuilder()
                .append("HTTP/1.1 ").append(status).append(' ').append(statusPhrase(status)).append("\r\n")
                .append("Content-Type: application/json\r\n")
                .append("Content-Length: ").append(bodyBytes.length).append("\r\n");
        if (sessionId != null) response.append("Mcp-Session-Id: ").append(sessionId).append("\r\n");
        response.append("Connection: close\r\n\r\n");

        OutputStream out = socket.getOutputStream();
        out.write(response.toString().getBytes(StandardCharsets.US_ASCII));
        out.write(bodyBytes);
        out.flush();
    }

    private static void sendSimpleResponse(Socket socket, int status) throws IOException {
        String response = "HTTP/1.1 " + status + " " + statusPhrase(status) + "\r\n"
                + "Content-Length: 0\r\n"
                + "Connection: close\r\n"
                + "\r\n";
        OutputStream out = socket.getOutputStream();
        out.write(response.getBytes(StandardCharsets.US_ASCII));
        out.flush();
    }

    private static String statusPhrase(int status) {
        return switch (status) {
            case 200 -> "OK";
            case 202 -> "Accepted";
            case 400 -> "Bad Request";
            case 404 -> "Not Found";
            case 405 -> "Method Not Allowed";
            case 500 -> "Internal Server Error";
            default -> "";
        };
    }

    /** Reads one CRLF- or LF-terminated line as ASCII; returns null at EOF with nothing read. */
    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int b;
        boolean any = false;
        while ((b = in.read()) != -1) {
            any = true;
            if (b == '\n') break;
            if (b != '\r') buf.write(b);
        }
        if (!any) return null;
        return buf.toString(StandardCharsets.ISO_8859_1);
    }

    /** `java -cp build_manual/libs/icarus-<version>.jar icarus.mcp.IcarusMcpTransportProvider`
     *  — end-to-end self-check: real HTTP client against a real McpSyncServer built on this
     *  transport, driving initialize -> notifications/initialized -> tools/call over the wire. */
    public static void main(String[] args) throws Exception {
        var jsonMapper = new io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper(new com.fasterxml.jackson.databind.ObjectMapper());
        var provider = new IcarusMcpTransportProvider(System.err::println, jsonMapper, "/mcp");

        var tool = new McpSchema.Tool("echo", "Echo", "Echoes back the given text",
                new McpSchema.JsonSchema("object", Map.of("text", Map.of("type", "string")), java.util.List.of("text"), false, null, null),
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
            var client = java.net.http.HttpClient.newHttpClient();
            String url = "http://127.0.0.1:" + port + "/mcp";

            var initResp = postJson(client, url, null,
                    "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2024-11-05\",\"capabilities\":{},\"clientInfo\":{\"name\":\"test\",\"version\":\"0\"}}}");
            String sessionId = initResp.headers().firstValue("Mcp-Session-Id").orElseThrow();
            assert initResp.body().contains("\"protocolVersion\"") : "no initialize result: " + initResp.body();

            postJson(client, url, sessionId, "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");

            var callResp = postJson(client, url, sessionId,
                    "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"echo\",\"arguments\":{\"text\":\"hi\"}}}");
            assert callResp.body().contains("echo:hi") : "tools/call did not echo back: " + callResp.body();

            System.out.println("IcarusMcpTransportProvider self-check passed (run with -ea to enforce).");
        } finally {
            server.closeGracefully();
            provider.stop();
        }
    }

    private static java.net.http.HttpResponse<String> postJson(java.net.http.HttpClient client, String url, String sessionId, String body) throws Exception {
        var builder = java.net.http.HttpRequest.newBuilder(java.net.URI.create(url))
                .header("Content-Type", "application/json")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body));
        if (sessionId != null) builder.header("Mcp-Session-Id", sessionId);
        var response = client.send(builder.build(), java.net.http.HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new RuntimeException("POST " + url + " failed: " + response.statusCode() + " " + response.body());
        }
        return response;
    }
}
