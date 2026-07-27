package icarus.core;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.logging.Logging;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

/**
 * Shared context for a single scan run.
 */
public final class ScanContext {

    private final MontoyaApi api;
    private final Logging logging;
    private final HttpRequestResponse target;
    private final ModuleConfig config;
    private final List<Finding> findings;
    private Consumer<String> liveLogger;

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

    public void setLiveLogger(Consumer<String> logger) {
        this.liveLogger = logger;
    }

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
        if (liveLogger != null) liveLogger.accept(message);
    }

    public void error(String message) {
        logging.logToError(message);
        if (liveLogger != null) liveLogger.accept("[ERROR] " + message);
    }
}
