package icarus.core;

import burp.api.montoya.MontoyaApi;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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
    private final List<ScanListener> listeners = new ArrayList<>();

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

    public void addListener(ScanListener listener) {
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
     * Records incoming findings: increments the count for duplicates (skipping suppressed
     * ones), or registers new ones (optionally raising a Burp audit issue). Returns the
     * subset that are newly-created or updated-but-actionable, for the caller to decide
     * whether to show a popup.
     */
    public List<Finding> processDeduplication(List<Finding> findings, boolean passive) {
        List<Finding> actionable = new ArrayList<>();

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
                notifyListenersOfUpdate();
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
                notifyListenersOfUpdate();
            }
        }
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
            case INFO           -> burp.api.montoya.scanner.audit.issues.AuditIssueSeverity.INFORMATION;
        };
    }

    private void notifyListenersOfUpdate() {
        uiDispatcher.accept(() -> {
            for (var listener : listeners) {
                listener.onScanComplete(new ArrayList<>(activeFindings.values()));
            }
        });
    }

    public interface ScanListener {
        void onScanComplete(List<FindingRecord> records);
    }
}
