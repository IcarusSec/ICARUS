package icarus.core;

import burp.api.montoya.MontoyaApi;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Owns finding state: deduplication, suppression, the audit log, and notifying
 * listeners of changes. Extracted from Orchestrator so scan execution and Burp
 * integration don't need to know how findings are tracked internally. Has no
 * UI-toolkit dependency of its own — the caller supplies how to dispatch
 * listener notifications onto the right thread (e.g. SwingUtilities::invokeLater).
 */
public final class FindingRegistry {

    private final MontoyaApi api;
    private final ModuleConfig config;
    private final Consumer<Runnable> uiDispatcher;

    private final Map<String, FindingRecord> activeFindings = new ConcurrentHashMap<>();
    private final List<String> auditLog = new ArrayList<>();
    private final List<Consumer<List<FindingRecord>>> listeners = new CopyOnWriteArrayList<>();

    // Set while a listener fan-out is already queued on the UI thread but hasn't run yet.
    // A rate-limit blast (or any bulk scan) can call notifyListenersOfUpdate() hundreds of
    // times in a burst — one per deduped finding, plus one per passive-scan hit on each of
    // the ~1500 blast responses. Each fan-out fully rebuilds every listener's UI (the whole
    // findings JTable, etc.), so without coalescing the EDT drowns in thousands of redundant
    // rebuilds and Burp freezes. The queued fan-out already reads activeFindings fresh, so
    // collapsing a storm of calls into one repaint loses nothing.
    private final AtomicBoolean fanOutPending = new AtomicBoolean(false);

    public FindingRegistry(MontoyaApi api, ModuleConfig config, Consumer<Runnable> uiDispatcher) {
        this.api = api;
        this.config = config;
        this.uiDispatcher = uiDispatcher;

        for (String hash : config.getStringList("suppressed_hashes")) {
            Finding dummy = Finding.builder("System", "DUMMY").build();
            FindingRecord fr = new FindingRecord(dummy);
            fr.setSuppressed(true);
            activeFindings.put(hash, fr);
        }
        logAudit("System initialized. Loaded " + config.getStringList("suppressed_hashes").size() + " suppression rules.");
    }

    public void logAudit(String action) {
        String timestamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        String entry = "[" + timestamp + "] " + action;
        synchronized (auditLog) {
            auditLog.add(entry);
        }
        api.logging().logToOutput(entry);
    }

    public List<String> getAuditLog() {
        synchronized (auditLog) {
            return new ArrayList<>(auditLog);
        }
    }

    public void addListener(Consumer<List<FindingRecord>> listener) {
        listeners.add(listener);
    }

    public void suppressFinding(String hash, String reason) {
        var record = activeFindings.get(hash);
        if (record != null) {
            record.setSuppressed(true);
            logAudit("User suppressed finding: " + hash + " (Reason: " + reason + ")");
            saveSuppressionConfig();
            notifyListenersOfUpdate();
        }
    }

    public void unsuppressFinding(String hash) {
        var record = activeFindings.get(hash);
        if (record != null) {
            record.setSuppressed(false);
            logAudit("User removed suppression for: " + hash);
            saveSuppressionConfig();
            notifyListenersOfUpdate();
        }
    }

    public Finding getFindingByHash(String hash) {
        FindingRecord record = activeFindings.get(hash);
        return record != null ? record.getFinding() : null;
    }

    public FindingRecord getRecordByHash(String hash) {
        return activeFindings.get(hash);
    }

    public List<FindingRecord> getAllFindingRecords() {
        return new ArrayList<>(activeFindings.values());
    }

    public List<FindingRecord> getPassiveFindings() {
        List<FindingRecord> results = new ArrayList<>();
        for (FindingRecord r : activeFindings.values()) {
            if (!r.isSuppressed() && r.getFinding().evidence() == null) {
                results.add(r);
            }
        }
        return results;
    }

    public void clearPassiveFindings() {
        activeFindings.entrySet().removeIf(e -> e.getValue().getFinding().evidence() == null);
        notifyListenersOfUpdate();
        logAudit("User cleared passive findings.");
    }

    /**
     * Wipe every tracked finding (active, passive, evidence-backed) and overwrite the
     * persisted {@code icarus_state} blob so an extension reload / Burp restart starts clean.
     * Suppression rules (stored separately in {@code config}) are kept. The audit log is
     * cleared apart from this one entry.
     */
    public void clearAllFindings() {
        long removed = activeFindings.values().stream()
                .filter(r -> !"DUMMY".equals(r.getFinding().type())).count();
        activeFindings.clear();
        synchronized (auditLog) { auditLog.clear(); }
        // Re-seed the suppression placeholders exactly as the constructor does, so a
        // previously-suppressed hash stays suppressed if it's ever re-detected.
        for (String hash : config.getStringList("suppressed_hashes")) {
            FindingRecord fr = new FindingRecord(Finding.builder("System", "DUMMY").build());
            fr.setSuppressed(true);
            activeFindings.put(hash, fr);
        }
        logAudit("User wiped the findings registry (" + removed + " findings removed; suppression rules kept).");
        api.persistence().extensionData().setString("icarus_state", serializeState());
        notifyListenersOfUpdate();
    }

    /**
     * Records incoming findings: increments the count for duplicates (skipping suppressed
     * ones), or registers new ones (optionally raising a Burp audit issue). Returns the
     * subset that are newly-created or updated-but-actionable, for the caller to decide
     * whether to show a popup.
     */
    public List<Finding> processDeduplication(List<Finding> findings, boolean passive) {
        List<Finding> actionable = new ArrayList<>();
        // One UI notification for the whole batch, fired once at the end (see the bottom of
        // this method), not one per finding. A Rate Limit blast hands this method dozens of
        // findings at once and its ~1500 responses each re-enter it via the passive scan; a
        // notify per finding used to queue thousands of full-UI rebuilds and freeze Burp.
        boolean changed = false;

        for (var finding : findings) {
            String hash = finding.similarityHash();
            var record = activeFindings.get(hash);

            if (record != null) {
                if (record.isSuppressed()) {
                    continue;
                }
                record.incrementCount();
                record.updateFinding(finding); // Keep the latest evidence and metadata
                logAudit("Duplicate finding incremented to " + record.getCount() + "x: " + hash);
                changed = true;
            } else {
                var newRecord = new FindingRecord(finding);
                activeFindings.put(hash, newRecord);

                logAudit("New finding identified: " + hash);
                actionable.add(finding);

                if (!passive && config.getBool("pv.create_audit_issues", true) && finding.evidence() != null) {
                    try {
                        createAuditIssue(finding);
                    } catch (Exception e) {
                        api.logging().logToError("Failed to create audit issue: " + e);
                    }
                }
                changed = true;
            }
        }
        // Single fan-out for the batch. notifyListenersOfUpdate() itself also coalesces
        // concurrent bursts (fanOutPending), so overlapping passive-scan re-entries collapse
        // to one repaint too.
        if (changed) notifyListenersOfUpdate();
        return actionable;
    }

    private void saveSuppressionConfig() {
        List<String> suppressed = new ArrayList<>();
        for (var entry : activeFindings.entrySet()) {
            if (entry.getValue().isSuppressed()) {
                suppressed.add(entry.getKey());
            }
        }
        config.set("suppressed_hashes", String.join("\n", suppressed));
        api.persistence().extensionData().setString("config", config.serialize());
    }

    private void createAuditIssue(Finding finding) {
        var issue = burp.api.montoya.scanner.audit.issues.AuditIssue.auditIssue(
            "ICARUS: " + finding.type(),
            finding.description() + "<br>Module: " + finding.module() + "<br>Path: " + finding.path(),
            "Review the finding and validate the vulnerability.",
            finding.evidence().request().url(),
            mapSeverity(finding.severity()),
            burp.api.montoya.scanner.audit.issues.AuditIssueConfidence.FIRM,
            null,
            null,
            mapSeverity(finding.severity()),
            finding.evidence()
        );
        api.siteMap().add(issue);
    }

    private burp.api.montoya.scanner.audit.issues.AuditIssueSeverity mapSeverity(Severity severity) {
        return switch (severity) {
            case CRITICAL, HIGH -> burp.api.montoya.scanner.audit.issues.AuditIssueSeverity.HIGH;
            case MEDIUM         -> burp.api.montoya.scanner.audit.issues.AuditIssueSeverity.MEDIUM;
            case LOW            -> burp.api.montoya.scanner.audit.issues.AuditIssueSeverity.LOW;
            case INFO, FIXED, NOT_FIXED -> burp.api.montoya.scanner.audit.issues.AuditIssueSeverity.INFORMATION;
        };
    }

    private void notifyListenersOfUpdate() {
        if (!fanOutPending.compareAndSet(false, true)) {
            return; // a fan-out is already queued — it will pick up the current state when it runs
        }
        uiDispatcher.accept(() -> {
            // Cleared before running (not after) so an update arriving mid-fan-out still
            // schedules a follow-up that reflects it.
            fanOutPending.set(false);
            DebugLog.timed(
                "FindingRegistry listener fan-out (" + activeFindings.size() + " findings, " + listeners.size() + " listeners)",
                () -> {
                    List<FindingRecord> snapshot = new ArrayList<>(activeFindings.values());
                    for (var listener : listeners) {
                        listener.accept(snapshot);
                    }
                });
        });
    }

    public String serializeState() {
        Map<String, Object> root = new java.util.LinkedHashMap<>();
        synchronized (auditLog) {
            root.put("auditLog", new ArrayList<>(auditLog));
        }
        
        List<Object> recordsJson = new ArrayList<>();
        for (Map.Entry<String, FindingRecord> entry : activeFindings.entrySet()) {
            FindingRecord r = entry.getValue();
            Finding f = r.getFinding();
            Map<String, Object> recordMap = new java.util.LinkedHashMap<>();
            recordMap.put("hash", entry.getKey());
            recordMap.put("count", String.valueOf(r.getCount()));
            recordMap.put("suppressed", r.isSuppressed());
            
            Map<String, Object> findingMap = new java.util.LinkedHashMap<>();
            findingMap.put("module", f.module());
            findingMap.put("type", f.type());
            findingMap.put("description", f.description());
            findingMap.put("severity", f.severity().name());
            findingMap.put("category", f.category().name());
            findingMap.put("path", f.path());
            findingMap.put("cweIds", new ArrayList<>(f.cweIds()));
            findingMap.put("metadata", new java.util.LinkedHashMap<>(f.metadata()));
            
            if (f.evidence() != null && f.evidence().request() != null) {
                Map<String, Object> evidenceJson = new java.util.LinkedHashMap<>();
                evidenceJson.put("request", java.util.Base64.getEncoder().encodeToString(f.evidence().request().toByteArray().getBytes()));
                // Persist the target binding — toByteArray() drops it, and without it a
                // reloaded finding's request has a null HttpService (breaks validate_finding
                // / rescan_finding resends). See also ProjectStateCodec.
                var svc = f.evidence().request().httpService();
                if (svc != null) {
                    evidenceJson.put("host", svc.host());
                    evidenceJson.put("port", svc.port());
                    evidenceJson.put("secure", svc.secure());
                }
                if (f.evidence().response() != null) {
                    evidenceJson.put("response", java.util.Base64.getEncoder().encodeToString(f.evidence().response().toByteArray().getBytes()));
                }
                findingMap.put("evidence", evidenceJson);
            }
            
            recordMap.put("finding", findingMap);
            recordsJson.add(recordMap);
        }
        root.put("activeFindings", recordsJson);
        return JsonParser.write(root);
    }

    @SuppressWarnings("unchecked")
    public void deserializeState(String json) {
        if (json == null || json.isEmpty()) return;
        try {
            Object parsed = JsonParser.parse(json);
            if (!(parsed instanceof Map<?, ?> root)) return;
            
            List<Object> log = (List<Object>) root.get("auditLog");
            if (log != null) {
                synchronized (auditLog) {
                    auditLog.clear();
                    for (Object o : log) auditLog.add(String.valueOf(o));
                }
            }
            
            List<Object> records = (List<Object>) root.get("activeFindings");
            if (records != null) {
                for (Object o : records) {
                    Map<String, Object> rMap = (Map<String, Object>) o;
                    String hash = String.valueOf(rMap.get("hash"));
                    int count = Integer.parseInt(String.valueOf(rMap.get("count")));
                    boolean suppressed = Boolean.TRUE.equals(rMap.get("suppressed"));
                    
                    Map<String, Object> fMap = (Map<String, Object>) rMap.get("finding");
                    if (fMap != null) {
                        Finding.Builder builder = Finding.builder(String.valueOf(fMap.get("module")), String.valueOf(fMap.get("type")))
                            .description(String.valueOf(fMap.getOrDefault("description", "")))
                            .severity(Severity.valueOf(String.valueOf(fMap.get("severity"))))
                            .category(Category.valueOf(String.valueOf(fMap.get("category"))))
                            .path(String.valueOf(fMap.getOrDefault("path", "")));
                            
                        Object cweRaw = fMap.get("cweIds");
                        if (cweRaw instanceof List<?> list) list.forEach(id -> builder.cwe(String.valueOf(id)));
                        
                        Object metaRaw = fMap.get("metadata");
                        if (metaRaw instanceof Map<?, ?> map) map.forEach((k, v) -> builder.meta(String.valueOf(k), String.valueOf(v)));
                        
                        Object evidenceRaw = fMap.get("evidence");
                        if (evidenceRaw instanceof Map<?, ?> evidenceMap) {
                            Object reqB64 = evidenceMap.get("request");
                            if (reqB64 != null) {
                                burp.api.montoya.core.ByteArray reqBytes = burp.api.montoya.core.ByteArray.byteArray(java.util.Base64.getDecoder().decode(String.valueOf(reqB64)));
                                Object evHost = evidenceMap.get("host");
                                Object evPort = evidenceMap.get("port");
                                burp.api.montoya.http.message.requests.HttpRequest request = (evHost != null && evPort instanceof Number)
                                        ? burp.api.montoya.http.message.requests.HttpRequest.httpRequest(
                                            burp.api.montoya.http.HttpService.httpService(String.valueOf(evHost), ((Number) evPort).intValue(), Boolean.TRUE.equals(evidenceMap.get("secure"))),
                                            reqBytes)
                                        : burp.api.montoya.http.message.requests.HttpRequest.httpRequest(reqBytes);
                                burp.api.montoya.http.message.responses.HttpResponse response = null;
                                Object resB64 = evidenceMap.get("response");
                                if (resB64 != null) {
                                    response = burp.api.montoya.http.message.responses.HttpResponse.httpResponse(burp.api.montoya.core.ByteArray.byteArray(java.util.Base64.getDecoder().decode(String.valueOf(resB64))));
                                }
                                builder.evidence(burp.api.montoya.http.message.HttpRequestResponse.httpRequestResponse(request, response));
                            }
                        }
                        
                        FindingRecord record = new FindingRecord(builder.build());
                        for (int i = 1; i < count; i++) record.incrementCount();
                        record.setSuppressed(suppressed);
                        
                        activeFindings.put(hash, record);
                    }
                }
            }
            notifyListenersOfUpdate();
        } catch (Exception e) {
            api.logging().logToError("Failed to deserialize state: " + e);
        }
    }
}
