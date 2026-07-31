package icarus.autoauth;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.Range;
import burp.api.montoya.http.HttpService;
import burp.api.montoya.http.handler.HttpRequestToBeSent;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import burp.api.montoya.ui.contextmenu.MessageEditorHttpRequestResponse;
import icarus.core.JsonParser;
import icarus.core.JsonPaths;
import icarus.core.ModuleConfig;

import javax.swing.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Background auth-token manager: replaces Burp's Macros with a "highlight and right-click"
 * workflow. Captures a token from a highlighted response value ("Set as Auth Token Source"),
 * maps where it needs to be re-injected ("Add Auth Token Destination"), then silently
 * refreshes and injects it into matching outgoing requests via {@link #processOutgoingRequest}.
 *
 * Deliberately NOT an {@link icarus.core.IcarusModule}: that contract is for stateless,
 * single-request scans invoked with a plain HttpRequestResponse, but AutoAuth is a persistent
 * background service whose configuration actions need text-selection offsets that contract
 * has no way to carry. Owned directly by {@link icarus.Orchestrator}, like EvidenceCapture.
 */
public final class AutoAuthModule {

    private enum TargetKind { HEADER, BODY }

    private record InjectionTarget(String host, TargetKind kind, String headerName, String headerPrefix, List<Object> bodyPath) {}

    private static final String K_ENABLED = "autoauth.enabled";
    private static final String K_REFRESH_MINUTES = "autoauth.refresh_minutes";
    private static final String K_SOURCE_HOST = "autoauth.source_host";
    private static final String K_SOURCE_PORT = "autoauth.source_port";
    private static final String K_SOURCE_SECURE = "autoauth.source_secure";
    private static final String K_SOURCE_RAW = "autoauth.source_raw";
    private static final String K_EXTRACTION_PATH = "autoauth.extraction_path";
    private static final String K_TARGETS = "autoauth.targets";

    private final MontoyaApi api;
    private final ModuleConfig config;

    // Guards both the check-then-refresh sequence (prevents concurrent threads from each
    // firing their own refresh request under load) and re-entrancy: the refresh's own
    // sendRequest() call routes back through the same HttpHandler on the same thread, and
    // isHeldByCurrentThread() lets processOutgoingRequest recognize and skip that nested call
    // instead of recursively refreshing forever or rewriting the login request itself.
    private final ReentrantLock refreshLock = new ReentrantLock();

    private HttpRequest sourceRequest;
    private List<Object> extractionPath = List.of();
    private final List<InjectionTarget> targets = new ArrayList<>();

    private volatile String cachedToken;
    private volatile long expiresAt;

    public AutoAuthModule(MontoyaApi api, ModuleConfig config) {
        this.api = api;
        this.config = config;
        loadSession();
    }

    // ── Context-menu actions ─────────────────────────────────────────────

    /** "ICARUS -> Set as Auth Token Source" — highlighted text in a response body. */
    public void setSourceFromSelection(MessageEditorHttpRequestResponse selection) {
        HttpRequestResponse rr = selection.requestResponse();
        HttpResponse response = rr.response();
        if (response == null || selection.selectionOffsets().isEmpty()) return;

        String highlighted = extractSelection(response.toString(), selection.selectionOffsets().get());
        if (highlighted == null || highlighted.isEmpty()) return;

        Object root;
        try {
            root = JsonParser.parse(response.bodyToString());
        } catch (Exception e) {
            warn("AutoAuth currently only supports JSON response bodies.");
            return;
        }

        List<List<Object>> matches = JsonPaths.findPathsByValue(root, highlighted);
        if (matches.isEmpty()) {
            warn("Could not locate the highlighted text as a JSON value in the response body.");
            return;
        }

        refreshLock.lock();
        try {
            this.sourceRequest = rr.request();
            this.extractionPath = matches.get(0);
            this.cachedToken = null;
            this.expiresAt = 0;
        } finally {
            refreshLock.unlock();
        }
        persistSession();

        String pathLabel = JsonPaths.pathToString(matches.get(0));
        String note = matches.size() > 1 ? pathLabel + " (first of " + matches.size() + " matches)" : pathLabel;
        api.logging().logToOutput("AutoAuth: source set — " + note);
        info("AutoAuth source set: " + note);
    }

    /** "ICARUS -> Add Auth Token Destination" — highlighted text in a request (header or body). */
    public void addDestinationFromSelection(MessageEditorHttpRequestResponse selection) {
        HttpRequestResponse rr = selection.requestResponse();
        HttpRequest request = rr.request();
        if (selection.selectionOffsets().isEmpty()) return;

        Range range = selection.selectionOffsets().get();
        String raw = request.toString();
        String highlighted = extractSelection(raw, range);
        if (highlighted == null || highlighted.isEmpty()) return;

        String host = request.httpService() != null ? request.httpService().host() : "";
        InjectionTarget target;

        if (range.startIndexInclusive() < request.bodyOffset()) {
            target = buildHeaderTarget(raw, range, host);
            if (target == null) {
                warn("Could not determine which header the highlighted text belongs to.");
                return;
            }
        } else {
            Object root;
            try {
                root = JsonParser.parse(request.bodyToString());
            } catch (Exception e) {
                warn("AutoAuth currently only supports JSON request bodies for body-based destinations.");
                return;
            }
            List<List<Object>> matches = JsonPaths.findPathsByValue(root, highlighted);
            if (matches.isEmpty()) {
                warn("Could not locate the highlighted text as a JSON value in the request body.");
                return;
            }
            target = new InjectionTarget(host, TargetKind.BODY, null, null, matches.get(0));
        }

        refreshLock.lock();
        try {
            targets.add(target);
        } finally {
            refreshLock.unlock();
        }
        persistSession();

        String desc = describeTarget(target);
        api.logging().logToOutput("AutoAuth: destination added — " + desc);
        info("AutoAuth destination added: " + desc);
    }

    public void clearSession() {
        refreshLock.lock();
        try {
            sourceRequest = null;
            extractionPath = List.of();
            targets.clear();
            cachedToken = null;
            expiresAt = 0;
        } finally {
            refreshLock.unlock();
        }
        persistSession();
    }

    public String statusSummary() {
        if (sourceRequest == null) return "No source configured.";
        String host = sourceRequest.httpService() != null ? sourceRequest.httpService().host() : "?";
        return "Source: " + host + sourceRequest.path() + " — " + targets.size() + " destination(s) mapped.";
    }

    // ── Request interception ─────────────────────────────────────────────

    /** Called from Orchestrator.handleHttpRequestToBeSent for every outgoing request. */
    public HttpRequest processOutgoingRequest(HttpRequestToBeSent request) {
        if (!config.getBool(K_ENABLED, true)) return request;
        if (sourceRequest == null || targets.isEmpty()) return request;

        String host = request.httpService() != null ? request.httpService().host() : "";
        List<InjectionTarget> applicable = new ArrayList<>();
        for (InjectionTarget t : targets) {
            if (t.host().equals(host) && targetPresent(request, t)) applicable.add(t);
        }
        if (applicable.isEmpty()) return request;

        if (refreshLock.isHeldByCurrentThread()) {
            // Reentrant: this IS the token-fetch sendRequest() below, routed back through
            // us on the same thread. Never rewrite the login request itself.
            return request;
        }

        refreshLock.lock();
        try {
            ensureFreshToken();
        } finally {
            refreshLock.unlock();
        }

        String token = cachedToken;
        if (token == null) return request; // refresh failed — fail open, don't break the request

        HttpRequest updated = request;
        for (InjectionTarget t : applicable) {
            updated = inject(updated, t, token);
        }
        return updated;
    }

    private void ensureFreshToken() {
        if (cachedToken != null && System.currentTimeMillis() < expiresAt) return; // refreshed while we waited for the lock
        if (sourceRequest == null) return;

        HttpRequestResponse result;
        try {
            result = api.http().sendRequest(sourceRequest);
        } catch (Exception e) {
            api.logging().logToError("AutoAuth: token refresh request failed: " + e);
            cachedToken = null;
            return;
        }
        if (result == null || result.response() == null) {
            api.logging().logToError("AutoAuth: token refresh got no response.");
            cachedToken = null;
            return;
        }

        Object root;
        try {
            root = JsonParser.parse(result.response().bodyToString());
        } catch (Exception e) {
            api.logging().logToError("AutoAuth: token refresh response wasn't valid JSON.");
            cachedToken = null;
            return;
        }

        Object value = JsonPaths.getAt(root, extractionPath);
        if (!(value instanceof String tokenValue) || tokenValue.isBlank()) {
            api.logging().logToError("AutoAuth: no token found at " + JsonPaths.pathToString(extractionPath) + " in the refresh response.");
            cachedToken = null;
            return;
        }

        cachedToken = tokenValue;
        int refreshMinutes = Math.max(1, config.getInt(K_REFRESH_MINUTES, 15));
        expiresAt = System.currentTimeMillis() + refreshMinutes * 60_000L;
        api.logging().logToOutput("AutoAuth: token refreshed, valid for " + refreshMinutes + " minutes.");
    }

    private boolean targetPresent(HttpRequest request, InjectionTarget t) {
        if (t.kind() == TargetKind.HEADER) {
            return request.hasHeader(t.headerName());
        }
        try {
            Object root = JsonParser.parse(request.bodyToString());
            return JsonPaths.getAt(root, t.bodyPath()) != null;
        } catch (Exception e) {
            return false;
        }
    }

    private HttpRequest inject(HttpRequest request, InjectionTarget t, String token) {
        if (t.kind() == TargetKind.HEADER) {
            String prefix = t.headerPrefix() == null ? "" : t.headerPrefix();
            return request.withUpdatedHeader(t.headerName(), prefix + token);
        }
        try {
            Object root = JsonParser.parse(request.bodyToString());
            if (JsonPaths.setAt(root, t.bodyPath(), token)) {
                return request.withBody(JsonParser.write(root));
            }
        } catch (Exception e) {
            api.logging().logToError("AutoAuth: failed to inject token into body: " + e);
        }
        return request;
    }

    // ── Selection parsing helpers ─────────────────────────────────────────

    private static String extractSelection(String raw, Range range) {
        if (range.startIndexInclusive() < 0 || range.endIndexExclusive() > raw.length()
                || range.startIndexInclusive() >= range.endIndexExclusive()) {
            return null; // stale offsets (editor content changed since the selection was made)
        }
        return raw.substring(range.startIndexInclusive(), range.endIndexExclusive());
    }

    /** Finds the header line containing the selection start and captures any preceding value prefix (e.g. "Bearer "). */
    private InjectionTarget buildHeaderTarget(String raw, Range range, String host) {
        int lineStart = raw.lastIndexOf('\n', range.startIndexInclusive() - 1) + 1;
        int lineEnd = raw.indexOf('\n', range.startIndexInclusive());
        if (lineEnd == -1) lineEnd = raw.length();
        String line = raw.substring(lineStart, lineEnd);

        int colon = line.indexOf(':');
        if (colon < 0) return null; // request line, or a malformed header line

        String headerName = line.substring(0, colon).trim();
        int valueStart = lineStart + colon + 1;
        while (valueStart < raw.length() && raw.charAt(valueStart) == ' ') valueStart++;

        int selStart = range.startIndexInclusive();
        String prefix = selStart > valueStart ? raw.substring(valueStart, Math.min(selStart, lineEnd)) : "";
        return new InjectionTarget(host, TargetKind.HEADER, headerName, prefix, null);
    }

    private String describeTarget(InjectionTarget t) {
        if (t.kind() == TargetKind.HEADER) {
            return t.host() + " header `" + t.headerName() + "`"
                    + (t.headerPrefix() != null && !t.headerPrefix().isEmpty() ? " (prefix \"" + t.headerPrefix() + "\")" : "");
        }
        return t.host() + " body " + JsonPaths.pathToString(t.bodyPath());
    }

    // ── Persistence (survives Burp/extension restarts, same pattern as RateLimitModule) ──

    private void loadSession() {
        String raw = config.getString(K_SOURCE_RAW, "");
        String host = config.getString(K_SOURCE_HOST, "");
        if (raw.isBlank() || host.isBlank()) return;

        int port = config.getInt(K_SOURCE_PORT, 443);
        boolean secure = config.getBool(K_SOURCE_SECURE, true);
        try {
            this.sourceRequest = HttpRequest.httpRequest(HttpService.httpService(host, port, secure), raw);
        } catch (Exception e) {
            api.logging().logToError("AutoAuth: failed to restore persisted source request: " + e);
            return;
        }
        this.extractionPath = JsonPaths.parsePath(config.getString(K_EXTRACTION_PATH, ""));

        targets.clear();
        for (String line : config.getStringList(K_TARGETS)) {
            InjectionTarget t = parseTarget(line);
            if (t != null) targets.add(t);
        }
    }

    private void persistSession() {
        if (sourceRequest == null) {
            config.set(K_SOURCE_RAW, "");
            config.set(K_SOURCE_HOST, "");
        } else {
            config.set(K_SOURCE_RAW, sourceRequest.toString());
            HttpService service = sourceRequest.httpService();
            config.set(K_SOURCE_HOST, service != null ? service.host() : "");
            config.set(K_SOURCE_PORT, service != null ? service.port() : 443);
            config.set(K_SOURCE_SECURE, service == null || service.secure());
        }
        config.set(K_EXTRACTION_PATH, JsonPaths.pathToString(extractionPath));

        StringBuilder sb = new StringBuilder();
        for (InjectionTarget t : targets) sb.append(serializeTarget(t)).append("\n");
        config.set(K_TARGETS, sb.toString());

        api.persistence().extensionData().setString("config", config.serialize());
    }

    private String serializeTarget(InjectionTarget t) {
        if (t.kind() == TargetKind.HEADER) {
            return "HEADER|" + t.host() + "|" + t.headerName() + "|" + (t.headerPrefix() == null ? "" : t.headerPrefix());
        }
        return "BODY|" + t.host() + "|" + JsonPaths.pathToString(t.bodyPath());
    }

    private InjectionTarget parseTarget(String line) {
        String[] parts = line.split("\\|", -1);
        if (parts.length < 3) return null;
        try {
            if ("HEADER".equals(parts[0])) {
                return new InjectionTarget(parts[1], TargetKind.HEADER, parts[2], parts.length > 3 ? parts[3] : "", null);
            } else if ("BODY".equals(parts[0])) {
                return new InjectionTarget(parts[1], TargetKind.BODY, null, null, JsonPaths.parsePath(parts[2]));
            }
        } catch (Exception ignored) {
            // malformed persisted line — skip it rather than fail the whole load
        }
        return null;
    }

    // ── UI feedback ───────────────────────────────────────────────────────

    private void warn(String message) {
        JOptionPane.showMessageDialog(api.userInterface().swingUtils().suiteFrame(), message, "AutoAuth", JOptionPane.WARNING_MESSAGE);
    }

    private void info(String message) {
        JOptionPane.showMessageDialog(api.userInterface().swingUtils().suiteFrame(), message, "AutoAuth", JOptionPane.INFORMATION_MESSAGE);
    }
}
