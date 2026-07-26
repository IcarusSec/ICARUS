package icarus.modules;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.handler.HttpResponseReceived;
import burp.api.montoya.http.message.HttpHeader;
import burp.api.montoya.http.message.responses.HttpResponse;
import icarus.core.Category;
import icarus.core.Finding;
import icarus.core.IcarusModule;
import icarus.core.ModuleConfig;
import icarus.core.Severity;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public class SensitiveHeaderModule implements IcarusModule {

    private final MontoyaApi api;

    private static final Pattern VERSION_PATTERN = Pattern.compile("[/\\s]\\d+\\.");
    private static final Pattern INTERNAL_IP_PATTERN = Pattern.compile("^(10\\.\\d+|172\\.(1[6-9]|2\\d|3[0-1])\\.\\d+|192\\.168\\.\\d+)\\.\\d+");

    public SensitiveHeaderModule(MontoyaApi api) {
        this.api = api;
    }

    @Override
    public String name() {
        return "Sensitive Headers";
    }

    @Override
    public List<Finding> run(HttpRequestResponse requestResponse, ModuleConfig config) {
        if (!config.getBool("sh.enabled", true)) {
            return List.of();
        }
        
        return analyze(requestResponse.response(), config, requestResponse);
    }
    
    public List<Finding> analyzeResponse(HttpResponseReceived responseReceived, ModuleConfig config) {
        if (!config.getBool("sh.enabled", true) || !config.getBool("sh.passive", true)) {
            return List.of();
        }
        
        return analyze(responseReceived, config, null);
    }

    private List<Finding> analyze(HttpResponse response, ModuleConfig config, HttpRequestResponse evidence) {
        if (response == null) {
            return List.of();
        }

        List<Finding> findings = new ArrayList<>();
        List<HttpHeader> headers = response.headers();

        boolean checkVersion = config.getBool("sh.check_version_disclosure", true);
        boolean checkMissing = config.getBool("sh.check_missing_security", true);
        boolean checkLeak = config.getBool("sh.check_sensitive_leak", true);
        boolean checkDebug = config.getBool("sh.check_debug_headers", true);
        boolean checkCookie = config.getBool("sh.check_cookie_flags", true);

        boolean hasHsts = false;
        boolean hasCsp = false;
        boolean hasXcto = false;
        boolean hasXfo = false;
        boolean hasRp = false;
        boolean hasPp = false;

        for (HttpHeader h : headers) {
            String name = h.name();
            String lowerName = name.toLowerCase();
            String value = h.value();
            String lowerValue = value.toLowerCase();

            // Version Disclosure
            if (checkVersion) {
                if (lowerName.equals("server") && VERSION_PATTERN.matcher(value).find()) {
                    addFinding(findings, evidence, "VERSION_DISCLOSURE", Severity.MEDIUM, Category.VERSION_DISCLOSURE, "Server header contains version: " + value);
                } else if (lowerName.equals("x-powered-by") || lowerName.equals("x-aspnet-version") || lowerName.equals("x-aspnetmvc-version") || lowerName.equals("x-generator")) {
                    addFinding(findings, evidence, "VERSION_DISCLOSURE", Severity.MEDIUM, Category.VERSION_DISCLOSURE, name + " header discloses technology/version: " + value);
                }
            }

            // Sensitive Data Leaks
            if (checkLeak) {
                if (lowerName.equals("authorization") || lowerName.equals("x-api-key") || lowerName.equals("x-auth-token")) {
                    addFinding(findings, evidence, "TOKEN_LEAK", Severity.HIGH, Category.HEADER_LEAK, "Sensitive header leaked in response: " + name);
                } else if (lowerName.equals("www-authenticate") && value.contains("internal")) { // simplified realm check
                    addFinding(findings, evidence, "AUTH_REALM_LEAK", Severity.HIGH, Category.HEADER_LEAK, "WWW-Authenticate realm contains internal info: " + value);
                } else if (lowerName.equals("x-forwarded-for") || lowerName.equals("x-real-ip") || lowerName.equals("x-originating-ip")) {
                    if (INTERNAL_IP_PATTERN.matcher(value).find()) {
                        addFinding(findings, evidence, "INTERNAL_IP_LEAK", Severity.HIGH, Category.HEADER_LEAK, "Internal IP leaked in header " + name + ": " + value);
                    }
                }
            }

            // Debug Headers
            if (checkDebug) {
                if (lowerName.equals("x-debug") || lowerName.equals("x-debug-token") || lowerName.equals("x-debug-token-link") || lowerName.equals("x-powered-by-plesk") || lowerName.startsWith("x-backend-") || lowerName.startsWith("x-runtime") || lowerName.startsWith("x-request-id")) {
                    addFinding(findings, evidence, "DEBUG_HEADER", Severity.MEDIUM, Category.HEADER_LEAK, "Debug/internal header present: " + name);
                }
            }

            // Cookie Flags
            if (checkCookie && lowerName.equals("set-cookie")) {
                if (!lowerValue.contains("secure")) {
                    addFinding(findings, evidence, "COOKIE_MISSING_SECURE", Severity.MEDIUM, Category.HEADER_LEAK, "Cookie missing Secure flag: " + value);
                }
                if (!lowerValue.contains("httponly")) {
                    addFinding(findings, evidence, "COOKIE_MISSING_HTTPONLY", Severity.MEDIUM, Category.HEADER_LEAK, "Cookie missing HttpOnly flag: " + value);
                }
                if (!lowerValue.contains("samesite")) {
                    addFinding(findings, evidence, "COOKIE_MISSING_SAMESITE", Severity.MEDIUM, Category.HEADER_LEAK, "Cookie missing SameSite attribute: " + value);
                }
            }

            // Missing Security Headers tracking
            if (checkMissing) {
                if (lowerName.equals("strict-transport-security")) hasHsts = true;
                else if (lowerName.equals("content-security-policy")) hasCsp = true;
                else if (lowerName.equals("x-content-type-options") && lowerValue.equals("nosniff")) hasXcto = true;
                else if (lowerName.equals("x-frame-options")) hasXfo = true;
                else if (lowerName.equals("referrer-policy")) hasRp = true;
                else if (lowerName.equals("permissions-policy")) hasPp = true;
            }
        }

        // Missing Security Headers check
        if (checkMissing) {
            if (!hasHsts) addFinding(findings, evidence, "MISSING_HSTS", Severity.LOW, Category.HEADER_MISSING, "Strict-Transport-Security header is missing");
            if (!hasCsp) addFinding(findings, evidence, "MISSING_CSP", Severity.LOW, Category.HEADER_MISSING, "Content-Security-Policy header is missing");
            if (!hasXcto) addFinding(findings, evidence, "MISSING_XCTO", Severity.LOW, Category.HEADER_MISSING, "X-Content-Type-Options header is missing or not 'nosniff'");
            if (!hasXfo) addFinding(findings, evidence, "MISSING_XFO", Severity.LOW, Category.HEADER_MISSING, "X-Frame-Options header is missing");
            if (!hasRp) addFinding(findings, evidence, "MISSING_RP", Severity.LOW, Category.HEADER_MISSING, "Referrer-Policy header is missing");
            if (!hasPp) addFinding(findings, evidence, "MISSING_PP", Severity.LOW, Category.HEADER_MISSING, "Permissions-Policy header is missing");
        }

        return findings;
    }

    private void addFinding(List<Finding> findings, HttpRequestResponse evidence, String type, Severity severity, Category category, String description) {
        findings.add(Finding.builder(name(), type)
                .description(description)
                .severity(severity)
                .category(category)
                .evidence(evidence) // Can be null for passive scans
                .build());
    }
}
