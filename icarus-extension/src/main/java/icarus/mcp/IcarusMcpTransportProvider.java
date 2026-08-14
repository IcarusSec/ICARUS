package icarus.mcp;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpServerSession;
import io.modelcontextprotocol.spec.McpServerTransport;
import io.modelcontextprotocol.spec.McpServerTransportProvider;

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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Hand-rolled MCP legacy-SSE transport against a raw {@link ServerSocket}, modeled on
 * mcp-core's own {@code HttpServletSseServerTransportProvider} for matching wire behavior
 * (same endpoint/event shape) without needing a servlet container (Jetty) just to run one
 * servlet inside a Burp extension.
 *
 * <p>Deliberately does NOT use JDK's built-in {@code com.sun.net.httpserver.HttpServer}: that
 * class lives in the {@code jdk.httpserver} platform module, which Burp's extension classloader
 * does not resolve (extensions are loaded via a plain {@code URLClassLoader} whose delegation
 * doesn't reach it), producing {@code ClassNotFoundException} at runtime despite compiling fine.
 * A raw socket server needs only {@code java.base}, which is always resolvable.
 *
 * <p>Every connection is handled as exactly one request/response with {@code Connection: close}
 * — no keep-alive, no chunked encoding, no persistent-connection request pipelining — since this
 * is a local, low-volume, single-user loopback server; the SSE response is close-delimited (no
 * {@code Content-Length}, body read until the socket closes), which is valid framing per RFC 7230
 * §3.3.3 and handled natively by every HTTP client tested against it, including
 * {@code java.net.http.HttpClient}.
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
    private ServerSocket serverSocket;
    private ExecutorService executor;
    private volatile boolean running;

    IcarusMcpTransportProvider(Consumer<String> errorLog, McpJsonMapper jsonMapper, String ssePath, String messagePath, String apiKey) {
        this.errorLog = errorLog;
        this.jsonMapper = jsonMapper;
        this.ssePath = ssePath;
        this.messagePath = messagePath;
        this.apiKey = apiKey;
    }

    /** Binds and starts the HTTP server on 127.0.0.1. {@code port} 0 picks an ephemeral port. Returns the bound port. */
    int start(int port) throws IOException {
        serverSocket = new ServerSocket();
        serverSocket.bind(new InetSocketAddress("127.0.0.1", port));
        running = true;
        // GET /sse blocks its handler thread for the lifetime of the session (see
        // SseSessionTransport#awaitClose) — a single-threaded executor would let one open SSE
        // connection starve every other request, including the POSTs that session needs to
        // receive. Daemon threads so a stuck connection can't block extensionUnloaded.
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
            String rawTarget = parts[1];

            Map<String, String> headers = new HashMap<>();
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                int idx = line.indexOf(':');
                if (idx > 0) headers.put(line.substring(0, idx).trim().toLowerCase(), line.substring(idx + 1).trim());
            }

            String path = rawTarget;
            String query = null;
            int q = rawTarget.indexOf('?');
            if (q >= 0) {
                path = rawTarget.substring(0, q);
                query = rawTarget.substring(q + 1);
            }

            String authHeader = headers.get("authorization");
            boolean authorized = authHeader != null && authHeader.equals("Bearer " + apiKey);

            if (ssePath.equals(path) && "GET".equalsIgnoreCase(method)) {
                handleSse(socket, authorized);
            } else if (messagePath.equals(path) && "POST".equalsIgnoreCase(method)) {
                int contentLength = 0;
                try {
                    contentLength = Integer.parseInt(headers.getOrDefault("content-length", "0"));
                } catch (NumberFormatException ignored) {
                    // treat as empty body
                }
                byte[] body = in.readNBytes(contentLength);
                handleMessage(socket, authorized, query, body);
            } else {
                sendSimpleResponse(socket, 404);
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

    private void handleSse(Socket socket, boolean authorized) {
        String sessionId = null;
        try {
            if (!authorized) {
                sendSimpleResponse(socket, 401);
                return;
            }

            sessionId = UUID.randomUUID().toString();
            OutputStream out = socket.getOutputStream();
            out.write(("HTTP/1.1 200 OK\r\n"
                    + "Content-Type: text/event-stream\r\n"
                    + "Cache-Control: no-cache\r\n"
                    + "Connection: close\r\n"
                    + "\r\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();

            SseSessionTransport transport = new SseSessionTransport(sessionId, out);
            sessions.put(sessionId, sessionFactory.create(transport));
            transport.writeEvent("endpoint", messagePath + "?sessionId=" + sessionId);

            // Blocks this pooled thread for the session's lifetime — see start()'s comment on
            // why the executor must support many concurrent threads.
            transport.awaitClose();
        } catch (IOException e) {
            errorLog.accept("ICARUS MCP SSE handling failed: " + e);
        } finally {
            if (sessionId != null) sessions.remove(sessionId);
            try {
                socket.close();
            } catch (IOException ignored) {
                // already closed/broken
            }
        }
    }

    private void handleMessage(Socket socket, boolean authorized, String query, byte[] body) {
        try {
            if (!authorized) {
                sendSimpleResponse(socket, 401);
                return;
            }

            String sessionId = queryParam(query, "sessionId");
            McpServerSession session = sessionId != null ? sessions.get(sessionId) : null;
            if (session == null) {
                sendSimpleResponse(socket, 404);
                return;
            }

            String bodyStr = new String(body, StandardCharsets.UTF_8);
            McpSchema.JSONRPCMessage message = McpSchema.deserializeJsonRpcMessage(jsonMapper, bodyStr);
            session.handle(message).block();

            sendSimpleResponse(socket, 202);
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
            case 401 -> "Unauthorized";
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

        SseSessionTransport(String sessionId, OutputStream out) {
            this.sessionId = sessionId;
            this.out = out;
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
