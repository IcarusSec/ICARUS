package icarus;

import burp.api.montoya.BurpExtension;
import burp.api.montoya.MontoyaApi;

import icarus.core.*;
import icarus.modules.*;
import icarus.evidence.EvidenceCapture;
import icarus.evidence.ReportGenerator;
import icarus.ui.IcarusTab;

import java.util.List;

/**
 * ICARUS — Burp Suite Extension entry point.
 *
 * Consolidates all ICARUS Bambda scripts into a single extension
 * with auto-evidence capture and structured reporting.
 */
public class Icarus implements BurpExtension {

    public static final String NAME = "ICARUS";
    public static final String VERSION = "1.1.4";

    @Override
    public void initialize(MontoyaApi api) {
        api.extension().setName(NAME + " v" + VERSION);

        var config = new ModuleConfig();
        applyDefaults(config);

        // Load persisted settings
        var persisted = api.persistence().extensionData().getString("config");
        if (persisted != null) {
            loadPersistedConfig(config, persisted);
        }

        List<IcarusModule> modules = List.of(
            new ParamValidatorModule(api),
            new HttpVerbModule(api),
            new JwtCheckerModule(api),
            new SensitiveHeaderModule(api),
            new PostmanExportModule(api),
            new RateLimitModule(api)
        );

        var evidenceCapture = new EvidenceCapture(api, config);
        var reportGenerator = new ReportGenerator(api);

        var orchestrator = new Orchestrator(api, modules, config, evidenceCapture, reportGenerator);

        // Register UI tab
        var tab = new IcarusTab(api, config, modules, orchestrator);
        api.userInterface().registerSuiteTab(NAME, tab.getComponent());

        // Register context menu
        api.userInterface().registerContextMenuItemsProvider(orchestrator);

        // Register passive HTTP handler for SensitiveHeaderModule
        api.http().registerHttpHandler(orchestrator);

        api.logging().logToOutput(NAME + " v" + VERSION + " loaded — " + modules.size() + " modules ready.");
    }

    private void applyDefaults(ModuleConfig config) {
        // ── ParamValidator defaults ──
        config.set("pv.enabled", true);
        config.set("pv.structural", true);
        config.set("pv.type_confusion", true);
        config.set("pv.boundary", true);
        config.set("pv.injection", true);
        config.set("pv.null_value", true);
        config.set("pv.field_removal", true);
        config.set("pv.empty_object", true);
        config.set("pv.empty_array", true);
        config.set("pv.empty_string", true);
        config.set("pv.long_string", true);
        config.set("pv.number_zero", true);
        config.set("pv.number_negative", true);
        config.set("pv.number_overflow", true);
        config.set("pv.integer_as_float", true);
        config.set("pv.boolean_flip", true);
        config.set("pv.sqli", true);
        config.set("pv.sqli_time", true);
        config.set("pv.xss", true);
        config.set("pv.path_traversal", true);
        config.set("pv.nosqli", true);
        config.set("pv.format_string", true);
        config.set("pv.unicode", true);
        config.set("pv.string_as_number", true);
        config.set("pv.string_as_boolean", true);
        config.set("pv.number_as_string", true);
        config.set("pv.number_as_numeric_string", true);
        config.set("pv.boolean_as_string", true);
        config.set("pv.boolean_as_number", true);
        config.set("pv.max_mutations", 60);
        config.set("pv.max_repeater", 10);
        config.set("pv.finding_status_min", 200);
        config.set("pv.finding_status_max", 299);
        config.set("pv.require_baseline", true);
        config.set("pv.filter_exact_match", false);
        config.set("pv.check_xss_reflection", true);
        config.set("pv.create_audit_issues", true);
        config.set("pv.long_string_length", 10000);
        config.set("pv.payload_sqli", "' OR '1'='1\n1' ORDER BY 1--\n' UNION SELECT NULL--\nadmin' --");
        config.set("pv.payload_sqli_time", "'; WAITFOR DELAY '0:0:10'--\npg_sleep(10)\nSLEEP(10)");
        config.set("pv.payload_sqli_time_delay_ms", 10000);
        config.set("pv.payload_xss", "<script>alert(1)</script>\n\"><img src=x onerror=prompt()>\njavascript:alert(1)");
        config.set("pv.payload_path_traversal", "../../../../etc/passwd\n..%2f..%2f..%2fwindows%2fwin.ini\n/etc/shadow");
        config.set("pv.payload_nosqli", "{\"$ne\": null}\n{\"$gt\": \"\"}");
        config.set("pv.payload_format_string", "%s%x%n\n%p%p%p");
        config.set("pv.payload_unicode", "‮test😀");

        // WAF defaults
        config.set("waf.detect_akamai", true);
        config.set("waf.safelist_payloads", "' OR 1=1--\n<svg/onload=alert(1)>");

        // ── HTTPVerbFuzz defaults ──
        config.set("hv.enabled", true);
        config.set("hv.test_get", true);
        config.set("hv.test_head", true);
        config.set("hv.test_post", false);
        config.set("hv.test_put", false);
        config.set("hv.test_patch", false);
        config.set("hv.test_delete", false);
        config.set("hv.test_options", true);
        config.set("hv.test_trace", true);
        config.set("hv.test_connect", false);
        config.set("hv.skip_original", true);
        config.set("hv.enable_state_changing", true);
        config.set("hv.body_strategy", "AUTO");
        config.set("hv.accepted_min", 200);
        config.set("hv.accepted_max", 299);
        config.set("hv.max_repeater", 12);
        config.set("hv.check_allow", true);
        config.set("hv.check_trace_reflection", true);
        config.set("hv.delay_ms", 0);

        // ── JWTChecker defaults ──
        config.set("jwt.enabled", true);

        // ── SensitiveHeaders defaults ──
        config.set("sh.enabled", true);
        config.set("sh.passive", true);
        config.set("sh.check_version_disclosure", true);
        config.set("sh.check_missing_security", true);
        config.set("sh.check_sensitive_leak", true);
        config.set("sh.check_debug_headers", true);
        config.set("sh.check_cookie_flags", true);

        // ── PostmanExport defaults ──
        config.set("export.enabled", true);

        // ── RateLimit defaults ──
        config.set("rl.enabled", true);
        config.set("rl.request_count", 50);
        config.set("rl.concurrency", 10);
        config.set("rl.cooldown_wait_ms", 5000);
        config.set("rl.bypass_headers", true);
        config.set("rl.bypass_path", true);
        config.set("rl.bypass_query", true);

        // ── Evidence defaults ──
        config.set("evidence.enabled", true);
        config.set("evidence.auto_capture", true);
        config.set("evidence.output_dir", System.getProperty("user.home") + "/icarus-reports");
        config.set("evidence.html_report", true);
        config.set("evidence.colorscheme", "Minimal Dark");
    }

    private void loadPersistedConfig(ModuleConfig config, String serialized) {
        try {
            config.loadSerialized(serialized);
        } catch (Exception ignored) {
            // Corrupted config — stick with defaults
        }
    }
}
