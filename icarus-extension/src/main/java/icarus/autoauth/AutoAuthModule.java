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
import java.util.Arrays;
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

    private record InjectionTarget(String host, TargetKind kind, String headerName, String headerPrefix, List<Object> bodyPath, int sourceIndex) {}

    /**
     * One "Set as Auth Token Source" capture. Multiple can coexist; destinations pick which one
     * feeds them. Each source refreshes under its own lock so independent sources (e.g. two
     * different login endpoints) refresh concurrently instead of queuing behind one another.
     */
    private static final class Source {
        final HttpRequest sourceRequest;
        final List<Object> extractionPath;
        final ReentrantLock lock = new ReentrantLock();
        volatile String cachedToken;
        volatile long expiresAt;

        Source(HttpRequest sourceRequest, List<Object> extractionPath) {
            this.sourceRequest = sourceRequest;
            this.extractionPath = extractionPath;
        }
    }

    private static final String K_ENABLED = "autoauth.enabled";
    private static final String K_REFRESH_MINUTES = "autoauth.refresh_minutes";
    private static final String K_SOURCE_COUNT = "autoauth.source_count";
    private static final String K_TARGETS = "autoauth.targets";

    private final MontoyaApi api;
    private final ModuleConfig config;

    // Guards mutation of the sources/targets lists themselves (add/clear). Refresh serialization
    // is per-Source (see Source.lock) so unrelated sources refresh concurrently instead of
    // queuing behind one global lock.
    private final ReentrantLock collectionsLock = new ReentrantLock();

    // The refresh's own sendRequest() call routes back through the same HttpHandler on the same
    // thread; this lets processOutgoingRequest recognize and skip that nested call instead of
    // recursively refreshing forever or rewriting the login request itself. A ThreadLocal instead
    // of a lock check because refreshes for different sources can now be in flight concurrently
    // on different threads, each needing to recognize only its own nested call.
    private static final ThreadLocal<Boolean> REFRESHING = ThreadLocal.withInitial(() -> false);

    private final List<Source> sources = new ArrayList<>();
    private final List<InjectionTarget> targets = new ArrayList<>();

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
        List<Object> chosen = choosePath(matches);
        if (chosen == null) return; // user cancelled the picker

        int index;
        collectionsLock.lock();
        try {
            sources.add(new Source(rr.request(), chosen));
            index = sources.size();
        } finally {
            collectionsLock.unlock();
        }
        persistSession();

        String note = "Source " + index + " (" + JsonPaths.pathToString(chosen) + ")";
        api.logging().logToOutput("AutoAuth: " + note + " set.");
        info("AutoAuth " + note + " set.");
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

        int sourceIndex = chooseSource();
        if (sourceIndex < 0) return; // no source configured, or user cancelled the picker

        String host = request.httpService() != null ? request.httpService().host() : "";
        InjectionTarget target;

        if (range.startIndexInclusive() < request.bodyOffset()) {
            target = buildHeaderTarget(raw, range, host, sourceIndex);
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
            List<Object> chosen = choosePath(matches);
            if (chosen == null) return; // user cancelled the picker
            target = new InjectionTarget(host, TargetKind.BODY, null, null, chosen, sourceIndex);
        }

        collectionsLock.lock();
        try {
            targets.add(target);
        } finally {
            collectionsLock.unlock();
        }
        persistSession();

        String desc = describeTarget(target);
        api.logging().logToOutput("AutoAuth: destination added — " + desc);
        info("AutoAuth destination added: " + desc);
    }

    public void clearSession() {
        collectionsLock.lock();
        try {
            sources.clear();
            targets.clear();
        } finally {
            collectionsLock.unlock();
        }
        persistSession();
    }

    public String statusSummary() {
        if (sources.isEmpty()) return "No source configured.";
        return sources.size() + " source(s) configured — " + targets.size() + " destination(s) mapped.";
    }

    public boolean isEnabled() {
        return config.getBool(K_ENABLED, true);
    }

    public void toggleEnabled() {
        boolean now = !isEnabled();
        config.set(K_ENABLED, now);
        api.persistence().extensionData().setString("config", config.serialize());
        api.logging().logToOutput("AutoAuth: " + (now ? "enabled" : "disabled"));
    }

    // ── Request interception ─────────────────────────────────────────────

    /**
     * Thread-local flag that modules set to {@code true} around their own
     * {@code api.http().sendRequest()} calls when the request carries a deliberately
     * tampered auth token that must NOT be overwritten.
     *
     * <p>This works because Montoya's {@code sendRequest()} invokes
     * {@link #processOutgoingRequest} on the <em>calling</em> thread (the same contract
     * the {@code REFRESHING} thread-local guard in {@link #ensureFreshToken} relies on).
     *
     * <p>Usage in a module:
     * <pre>{@code
     *   AutoAuthModule.BYPASS_INJECTION.set(true);
     *   try {
     *       var result = api.http().sendRequest(mutatedReq);
     *   } finally {
     *       AutoAuthModule.BYPASS_INJECTION.set(false);
     *   }
     * }</pre>
     */
    public static final ThreadLocal<Boolean> BYPASS_INJECTION = ThreadLocal.withInitial(() -> false);

    /** Called from Orchestrator.handleHttpRequestToBeSent for every outgoing request. */
    public HttpRequest processOutgoingRequest(HttpRequestToBeSent request) {
        if (REFRESHING.get()) {
            // Reentrant: this IS the token-fetch sendRequest() below, routed back through
            // us on the same thread. Never rewrite the login request itself.
            return request;
        }
        if (Boolean.TRUE.equals(BYPASS_INJECTION.get())) {
            // A scan module (e.g. JWT Checker) is sending a deliberately tampered request
            // on this thread — do not overwrite it with a fresh token.
            return request;
        }
        return injectIfApplicable(request);
    }

    /**
     * Applies the same token injection {@link #processOutgoingRequest} does, for callers that
     * have a plain {@link HttpRequest} rather than a live {@link HttpRequestToBeSent} — e.g.
     * Orchestrator building manual/smart evidence from a request that already went out on the
     * wire with the token injected, so the captured evidence shows what was actually sent
     * instead of the stale pre-injection body.
     */
    public HttpRequest injectIfApplicable(HttpRequest request) {
        if (!config.getBool(K_ENABLED, true)) return request;
        if (sources.isEmpty() || targets.isEmpty()) return request;

        String host = request.httpService() != null ? request.httpService().host() : "";
        List<InjectionTarget> applicable = new ArrayList<>();
        for (InjectionTarget t : targets) {
            boolean sourceOk = t.sourceIndex() >= 0 && t.sourceIndex() < sources.size() && sources.get(t.sourceIndex()) != null;
            if (sourceOk && t.host().equals(host) && targetPresent(request, t)) {
                applicable.add(t);
            }
        }
        if (applicable.isEmpty()) return request;

        // Each call locks only that target's own Source (see ensureFreshToken), so refreshing
        // two unrelated sources for two concurrent requests no longer queues behind each other.
        for (InjectionTarget t : applicable) {
            ensureFreshToken(sources.get(t.sourceIndex()));
        }

        HttpRequest updated = request;
        for (InjectionTarget t : applicable) {
            String token = sources.get(t.sourceIndex()).cachedToken;
            if (token == null) continue; // that source's refresh failed — fail open for this target only
            updated = inject(updated, t, token);
        }
        return updated;
    }

    /**
     * Locks only {@code s} — refreshing one source no longer blocks a concurrent request that
     * needs a different source's token.
     */
    private void ensureFreshToken(Source s) {
        s.lock.lock();
        try {
            if (s.cachedToken != null && System.currentTimeMillis() < s.expiresAt) return; // refreshed while we waited for this source's lock

            HttpRequestResponse result;
            REFRESHING.set(true);
            try {
                result = api.http().sendRequest(s.sourceRequest);
            } catch (Exception e) {
                api.logging().logToError("AutoAuth: token refresh request failed: " + e);
                s.cachedToken = null;
                return;
            } finally {
                REFRESHING.set(false);
            }
            if (result == null || result.response() == null) {
                api.logging().logToError("AutoAuth: token refresh got no response.");
                s.cachedToken = null;
                return;
            }

            Object root;
            try {
                root = JsonParser.parse(result.response().bodyToString());
            } catch (Exception e) {
                api.logging().logToError("AutoAuth: token refresh response wasn't valid JSON.");
                s.cachedToken = null;
                return;
            }

            Object value = JsonPaths.getAt(root, s.extractionPath);
            if (!(value instanceof String tokenValue) || tokenValue.isBlank()) {
                api.logging().logToError("AutoAuth: no token found at " + JsonPaths.pathToString(s.extractionPath) + " in the refresh response.");
                s.cachedToken = null;
                return;
            }

            s.cachedToken = tokenValue;
            int refreshMinutes = Math.max(1, config.getInt(K_REFRESH_MINUTES, 15));
            s.expiresAt = System.currentTimeMillis() + refreshMinutes * 60_000L;
            api.logging().logToOutput("AutoAuth: token refreshed, valid for " + refreshMinutes + " minutes.");
        } finally {
            s.lock.unlock();
        }
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

    /**
     * When the highlighted text matches more than one JSON leaf, asks the user which one was
     * meant instead of silently defaulting to the first. Returns {@code null} if the user
     * cancels the dialog.
     */
    private List<Object> choosePath(List<List<Object>> matches) {
        if (matches.size() == 1) return matches.get(0);

        String[] options = matches.stream().map(JsonPaths::pathToString).toArray(String[]::new);
        String choice = (String) JOptionPane.showInputDialog(
                api.userInterface().swingUtils().suiteFrame(),
                "Multiple occurrences found. Which path should be used?",
                "AutoAuth", JOptionPane.QUESTION_MESSAGE, null, options, options[0]);
        if (choice == null) return null;

        int idx = Arrays.asList(options).indexOf(choice);
        return matches.get(idx < 0 ? 0 : idx);
    }

    /**
     * Picks which configured {@link Source} a new destination should pull its token from.
     * Auto-selects when there's exactly one; returns -1 if none exist yet or the user cancels.
     */
    private int chooseSource() {
        List<Integer> validIndices = new ArrayList<>();
        for (int i = 0; i < sources.size(); i++) {
            if (sources.get(i) != null) validIndices.add(i);
        }

        if (validIndices.isEmpty()) {
            warn("No Auth Token Source configured yet. Use \"Set as Auth Token Source\" first.");
            return -1;
        }
        if (validIndices.size() == 1) return validIndices.get(0);

        String[] options = new String[validIndices.size()];
        for (int i = 0; i < options.length; i++) options[i] = describeSource(validIndices.get(i));
        String choice = (String) JOptionPane.showInputDialog(
                api.userInterface().swingUtils().suiteFrame(),
                "Multiple sources configured. Which one should this destination use?",
                "AutoAuth", JOptionPane.QUESTION_MESSAGE, null, options, options[0]);
        if (choice == null) return -1;

        int idx = Arrays.asList(options).indexOf(choice);
        return validIndices.get(idx < 0 ? 0 : idx);
    }

    private String describeSource(int i) {
        Source s = sources.get(i);
        String host = s.sourceRequest.httpService() != null ? s.sourceRequest.httpService().host() : "?";
        return "Source " + (i + 1) + ": " + host + s.sourceRequest.path() + " → " + JsonPaths.pathToString(s.extractionPath);
    }

    private static String extractSelection(String raw, Range range) {
        if (range.startIndexInclusive() < 0 || range.endIndexExclusive() > raw.length()
                || range.startIndexInclusive() >= range.endIndexExclusive()) {
            return null; // stale offsets (editor content changed since the selection was made)
        }
        return raw.substring(range.startIndexInclusive(), range.endIndexExclusive());
    }

    /** Finds the header line containing the selection start and captures any preceding value prefix (e.g. "Bearer "). */
    private InjectionTarget buildHeaderTarget(String raw, Range range, String host, int sourceIndex) {
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
        return new InjectionTarget(host, TargetKind.HEADER, headerName, prefix, null, sourceIndex);
    }

    private String describeTarget(InjectionTarget t) {
        String base;
        if (t.kind() == TargetKind.HEADER) {
            base = t.host() + " header `" + t.headerName() + "`"
                    + (t.headerPrefix() != null && !t.headerPrefix().isEmpty() ? " (prefix \"" + t.headerPrefix() + "\")" : "");
        } else {
            base = t.host() + " body " + JsonPaths.pathToString(t.bodyPath());
        }
        return base + " ← Source " + (t.sourceIndex() + 1);
    }

    // ── Persistence (survives Burp/extension restarts, same pattern as RateLimitModule) ──

    /**
     * Each source's raw HTTP request text contains real embedded newlines, so it can't be
     * packed into the same newline-joined list format {@link #K_TARGETS} uses (that would
     * fragment mid-request). Every source instead gets its own numbered key group.
     */
    private static String sourceKey(int i, String suffix) {
        return "autoauth.source." + i + "." + suffix;
    }

    private void loadSession() {
        sources.clear();
        int count = config.getInt(K_SOURCE_COUNT, 0);
        for (int i = 0; i < count; i++) {
            String raw = config.getString(sourceKey(i, "raw"), "");
            String host = config.getString(sourceKey(i, "host"), "");
            if (raw.isBlank() || host.isBlank()) continue;

            int port = config.getInt(sourceKey(i, "port"), 443);
            boolean secure = config.getBool(sourceKey(i, "secure"), true);
            try {
                HttpRequest req = HttpRequest.httpRequest(HttpService.httpService(host, port, secure), raw);
                List<Object> path = JsonPaths.parsePath(config.getString(sourceKey(i, "extraction_path"), ""));
                sources.add(new Source(req, path));
            } catch (Exception e) {
                api.logging().logToError("AutoAuth: failed to restore persisted source " + (i + 1) + ": " + e);
                sources.add(null); // keep the index aligned with persisted targets' sourceIndex
            }
        }

        targets.clear();
        for (String line : config.getStringList(K_TARGETS)) {
            InjectionTarget t = parseTarget(line);
            if (t != null) targets.add(t);
        }
    }

    private void persistSession() {
        config.set(K_SOURCE_COUNT, sources.size());
        for (int i = 0; i < sources.size(); i++) {
            Source s = sources.get(i);
            config.set(sourceKey(i, "raw"), s.sourceRequest.toString());
            HttpService service = s.sourceRequest.httpService();
            config.set(sourceKey(i, "host"), service != null ? service.host() : "");
            config.set(sourceKey(i, "port"), service != null ? service.port() : 443);
            config.set(sourceKey(i, "secure"), service == null || service.secure());
            config.set(sourceKey(i, "extraction_path"), JsonPaths.pathToString(s.extractionPath));
        }

        StringBuilder sb = new StringBuilder();
        for (InjectionTarget t : targets) sb.append(serializeTarget(t)).append("\n");
        config.set(K_TARGETS, sb.toString());

        api.persistence().extensionData().setString("config", config.serialize());
    }

    private String serializeTarget(InjectionTarget t) {
        if (t.kind() == TargetKind.HEADER) {
            return "HEADER|" + t.host() + "|" + t.headerName() + "|" + (t.headerPrefix() == null ? "" : t.headerPrefix()) + "|" + t.sourceIndex();
        }
        return "BODY|" + t.host() + "|" + JsonPaths.pathToString(t.bodyPath()) + "|" + t.sourceIndex();
    }

    private InjectionTarget parseTarget(String line) {
        String[] parts = line.split("\\|", -1);
        if (parts.length < 3) return null;
        try {
            if ("HEADER".equals(parts[0])) {
                int sourceIndex = Integer.parseInt(parts[4]);
                return new InjectionTarget(parts[1], TargetKind.HEADER, parts[2], parts[3], null, sourceIndex);
            } else if ("BODY".equals(parts[0])) {
                int sourceIndex = Integer.parseInt(parts[3]);
                return new InjectionTarget(parts[1], TargetKind.BODY, null, null, JsonPaths.parsePath(parts[2]), sourceIndex);
            }
        } catch (Exception ignored) {
            // malformed/pre-multi-source persisted line — skip it rather than fail the whole load
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
