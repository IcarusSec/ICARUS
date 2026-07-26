package icarus.core;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.logging.Logging;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Shared context for a single scan run.
 *
 * Tracks findings, provides API access, and coordinates
 * the modules→evidence→report pipeline.
 */
public final class ScanContext {

    private final MontoyaApi api;
    private final Logging logging;
    private final HttpRequestResponse target;
    private final ModuleConfig config;
    private final List<Finding> findings;

    public ScanContext(MontoyaApi api, HttpRequestResponse target, ModuleConfig config) {
        this.api = api;
        this.logging = api.logging();
        this.target = target;
        this.config = config;
        this.findings = new ArrayList<>();
    }

    public MontoyaApi api()               { return api; }
    public Logging logging()              { return logging; }
    public HttpRequestResponse target()   { return target; }
    public ModuleConfig config()          { return config; }

    public void addFinding(Finding finding) {
        findings.add(finding);
    }

    public void addFindings(List<Finding> batch) {
        findings.addAll(batch);
    }

    public List<Finding> findings() {
        return Collections.unmodifiableList(findings);
    }

    public void log(String message) {
        logging.logToOutput(message);
    }

    public void error(String message) {
        logging.logToError(message);
    }
}
