package icarus.modules;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.handler.HttpResponseReceived;
import burp.api.montoya.http.message.HttpHeader;
import burp.api.montoya.http.message.MimeType;
import burp.api.montoya.http.message.responses.HttpResponse;
import icarus.core.Category;
import icarus.core.Finding;
import icarus.core.IcarusModule;
import icarus.core.ModuleConfig;
import icarus.core.Severity;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.HashSet;
import java.util.function.Consumer;
import java.util.regex.Pattern;

public class SensitiveHeaderModule implements IcarusModule {

    private final MontoyaApi api;

    private static final Pattern VERSION_PATTERN = Pattern.compile("[/\\s]\\d+\\.");
    private static final Pattern INTERNAL_IP_PATTERN = Pattern.compile("^(10\\.\\d+|172\\.(1[6-9]|2\\d|3[0-1])\\.\\d+|192\\.168\\.\\d+)\\.\\d+");

    // ==========================================
    // CWE-200: PII & National Identification Numbers
    // ==========================================
    private static final Pattern US_SSN_PATTERN = Pattern.compile("\\b(?!000|666)[0-8]\\d{2}-(?!00)\\d{2}-(?!0000)\\d{4}\\b");
    private static final Pattern UK_NINO_PATTERN = Pattern.compile("\\b[A-CEGHJ-PR-TW-Z][A-CEGHJ-NPR-TW-Z]\\s?\\d{2}\\s?\\d{2}\\s?\\d{2}\\s?[A-D]\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern CA_SIN_PATTERN = Pattern.compile("\\b\\d{3}[\\s-]\\d{3}[\\s-]\\d{3}\\b");
    private static final Pattern BR_CPF_PATTERN = Pattern.compile("\\b\\d{3}\\.\\d{3}\\.\\d{3}-\\d{2}\\b");
    private static final Pattern FR_NIR_PATTERN = Pattern.compile("\\b[12]\\s?\\d{2}\\s?(?:0[1-9]|1[0-2])\\s?(?:2[AB]|\\d{2})\\s?\\d{3}\\s?\\d{3}\\s?\\d{2}\\b");

    // ==========================================
    // CWE-200: Financial Information
    // ==========================================
    // Structural match only - MUST be paired with isValidLuhn() before raising a finding.
    private static final Pattern CREDIT_CARD_PATTERN = Pattern.compile("\\b(?:\\d[ -]*?){13,16}\\b");
    // Structural match only, no per-country length/checksum validation - kept low-confidence.
    private static final Pattern IBAN_PATTERN = Pattern.compile("\\b[A-Z]{2}\\d{2}[A-Z0-9]{11,30}\\b");

    // ==========================================
    // CWE-200: Backend Information & Stack Traces
    // ==========================================
    private static final Pattern BACKEND_INFO_PATTERN = Pattern.compile(
        "(?i)(" +
        "java\\.[a-z0-9_\\.]+(?:Exception|Error)|" +
        "org\\.(?:apache|springframework|hibernate)\\.|" +
        "at java\\.base/|" +
        "Traceback \\(most recent call last\\):|" +
        "File \"[^\"]+\", line \\d+, in|" +
        "werkzeug\\.exceptions\\.|" +
        "Fatal error: Uncaught|" +
        "PHP (?:Warning|Notice|Parse error):|" +
        "Stack trace:|" +
        "\\w+\\.rb:\\d+:in|" +
        "actionpack-[\\d\\.]+/lib/|" +
        "Error: .*\\n\\s+at (?:.*/)?.*:\\d+:\\d+|" +
        "node_modules/express/|" +
        "at \\S+\\.pm line \\d+" +
        ")"
    );

    // ==========================================
    // CWE-200: Infrastructure & Network Leaks
    // ==========================================
    // Named distinctly from INTERNAL_IP_PATTERN above (narrower, anchored, IPv4-only) to avoid a
    // duplicate-field clash; this one is broader (IPv6, unanchored) for general header scanning.
    private static final Pattern CWE200_INTERNAL_IP_PATTERN = Pattern.compile("\\b(10\\.\\d+\\.\\d+\\.\\d+|192\\.168\\.\\d+\\.\\d+|172\\.(?:1[6-9]|2\\d|3[0-1])\\.\\d+\\.\\d+|fc00:[a-f0-9:]+|fe80:[a-f0-9:]+)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern INTERNAL_DOMAIN_PATTERN = Pattern.compile("\\b[a-zA-Z0-9.-]+\\.(local|corp|internal|lan|pri)\\b", Pattern.CASE_INSENSITIVE);

    public SensitiveHeaderModule(MontoyaApi api) {
        this.api = api;
    }

    @Override
    public String name() {
        return "Sensitive Headers";
    }

    @Override
    public List<Finding> run(HttpRequestResponse requestResponse, ModuleConfig config, Consumer<String> logger) {
        if (!config.getBool("sh.enabled", true)) {
            return List.of();
        }
        
        return analyze(requestResponse.response(), config, requestResponse);
    }
    
    public List<Finding> analyzeResponse(HttpResponseReceived responseReceived, ModuleConfig config) {
        if (!config.getBool("sh.enabled", true) || !config.getBool("sh.passive", true)) {
            return List.of();
        }

        // Was null — passive findings carried no request/response evidence at all, which broke
        // anything downstream that needs to resend the finding (validate_finding, evidence
        // capture's own screenshot rendering). initiatingRequest() is right there on the passive
        // handler's own response object, no extra round-trip needed to attach it.
        HttpRequestResponse evidence = HttpRequestResponse.httpRequestResponse(responseReceived.initiatingRequest(), responseReceived);
        return analyze(responseReceived, config, evidence);
    }

    private List<Finding> analyze(HttpResponse response, ModuleConfig config, HttpRequestResponse evidence) {
        if (response == null) {
            return List.of();
        }

        List<Finding> findings = new ArrayList<>();
        List<HttpHeader> headers = response.headers();

        // Without a request we can't tell the scheme; assume HTTPS so nothing is silently skipped.
        boolean https = evidence == null || evidence.request() == null || evidence.request().httpService() == null
                || evidence.request().httpService().secure();
        int status = response.statusCode();
        // Page-level headers (CSP, XFO, Referrer/Permissions-Policy) only protect documents a
        // browser renders — flagging them on every JSON/JS/image/font/redirect response turned
        // one real observation into hundreds of duplicates per host.
        boolean htmlDocument = response.statedMimeType() == MimeType.HTML || response.inferredMimeType() == MimeType.HTML;
        boolean finalResponse = status >= 200 && status <= 299;

        boolean checkVersion = config.getBool("sh.check_version_disclosure", true);
        boolean checkMissing = config.getBool("sh.check_missing_security", true);
        boolean checkLeak = config.getBool("sh.check_sensitive_leak", true);
        boolean checkDebug = config.getBool("sh.check_debug_headers", true);
        boolean checkCookie = config.getBool("sh.check_cookie_flags", true);
        boolean checkCwe200Pii = config.getBool("sh.check_cwe200_pii", true);
        boolean checkCwe200Financial = config.getBool("sh.check_cwe200_financial", true);
        boolean checkCwe200Backend = config.getBool("sh.check_cwe200_backend", true);
        boolean checkCwe200Infra = config.getBool("sh.check_cwe200_infra", true);
        boolean redactPiiValues = config.getBool("sh.redact_pii_values", false);

        boolean hasHsts = false;
        boolean hasCsp = false;
        boolean hasXcto = false;
        boolean hasXfo = false;
        boolean hasRp = false;
        boolean hasPp = false;
        boolean hasFrameAncestors = false;

        for (HttpHeader h : headers) {
            String name = h.name();
            String lowerName = name.toLowerCase();
            String value = h.value();
            String lowerValue = value.toLowerCase();

            // Version Disclosure
            if (checkVersion) {
                if (lowerName.equals("server") && VERSION_PATTERN.matcher(value).find()) {
                    addHostFinding(findings, evidence, "VERSION_DISCLOSURE", Severity.MEDIUM, Category.VERSION_DISCLOSURE, "Server header contains version: " + value, lowerName);
                } else if (lowerName.equals("x-powered-by") || lowerName.equals("x-aspnet-version") || lowerName.equals("x-aspnetmvc-version") || lowerName.equals("x-generator")) {
                    addHostFinding(findings, evidence, "VERSION_DISCLOSURE", Severity.MEDIUM, Category.VERSION_DISCLOSURE, name + " header discloses technology/version: " + value, lowerName);
                }
            }

            // Sensitive Data Leaks
            if (checkLeak) {
                if (lowerName.equals("authorization") || lowerName.equals("x-api-key") || lowerName.equals("x-auth-token")) {
                    addFinding(findings, evidence, "TOKEN_LEAK", Severity.HIGH, Category.HEADER_LEAK, "Sensitive header leaked in response: " + name + " = " + (redactPiiValues ? "[REDACTED]" : value));
                } else if (lowerName.equals("www-authenticate") && value.contains("internal")) { // simplified realm check
                    addHostFinding(findings, evidence, "AUTH_REALM_LEAK", Severity.HIGH, Category.HEADER_LEAK, "WWW-Authenticate realm contains internal info: " + value, lowerName);
                } else if (lowerName.equals("x-forwarded-for") || lowerName.equals("x-real-ip") || lowerName.equals("x-originating-ip")) {
                    if (INTERNAL_IP_PATTERN.matcher(value).find()) {
                        addFinding(findings, evidence, "INTERNAL_IP_LEAK", Severity.HIGH, Category.HEADER_LEAK, "Internal IP leaked in header " + name + ": " + value);
                    }
                }
            }

            // Debug Headers
            if (checkDebug) {
                if (lowerName.equals("x-debug") || lowerName.equals("x-debug-token") || lowerName.equals("x-debug-token-link") || lowerName.equals("x-powered-by-plesk") || lowerName.startsWith("x-backend-") || lowerName.startsWith("x-runtime")) {
                    // X-Request-Id deliberately not listed: it's an opaque correlation id most
                    // modern stacks emit on every response, not debug/internal information.
                    addHostFinding(findings, evidence, "DEBUG_HEADER", Severity.MEDIUM, Category.HEADER_LEAK, "Debug/internal header present: " + name + " = " + (redactPiiValues ? "[REDACTED]" : value), lowerName);
                }
            }

            // Cookie Flags
            if (checkCookie && lowerName.equals("set-cookie") && !isCookieDeletion(value)) {
                // Attribute names are matched exactly — a substring check let a cookie merely
                // NAMED/VALUED e.g. "secure_session" or "samesite_pref" hide a missing flag.
                Set<String> attrs = cookieAttributes(value);
                String cookie = "cookie " + cookieName(value);
                if (https && !attrs.contains("secure")) {
                    addHostFinding(findings, evidence, "COOKIE_MISSING_SECURE", Severity.MEDIUM, Category.HEADER_LEAK, "Cookie missing Secure flag: " + value, cookie);
                }
                if (!attrs.contains("httponly")) {
                    addHostFinding(findings, evidence, "COOKIE_MISSING_HTTPONLY", Severity.MEDIUM, Category.HEADER_LEAK, "Cookie missing HttpOnly flag: " + value, cookie);
                }
                if (!attrs.contains("samesite")) {
                    addHostFinding(findings, evidence, "COOKIE_MISSING_SAMESITE", Severity.MEDIUM, Category.HEADER_LEAK, "Cookie missing SameSite attribute: " + value, cookie);
                }
            }

            // CWE-200: PII / National IDs
            if (checkCwe200Pii) {
                String valDisplay = redactPiiValues ? "[REDACTED]" : value;
                if (US_SSN_PATTERN.matcher(value).find()) {
                    addFinding(findings, evidence, "PII_US_SSN_LEAK", Severity.HIGH, Category.HEADER_LEAK, "Potential US SSN leaked in header '" + name + "': " + valDisplay);
                } else if (UK_NINO_PATTERN.matcher(value).find()) {
                    addFinding(findings, evidence, "PII_UK_NINO_LEAK", Severity.HIGH, Category.HEADER_LEAK, "Potential UK NINO leaked in header '" + name + "': " + valDisplay);
                } else if (matchesWithLuhn(CA_SIN_PATTERN, value, 9, 9)) {
                    addFinding(findings, evidence, "PII_CA_SIN_LEAK", Severity.LOW, Category.HEADER_LEAK, "Potential Canada SIN leaked in header '" + name + "': " + valDisplay);
                } else if (BR_CPF_PATTERN.matcher(value).find()) {
                    addFinding(findings, evidence, "PII_BR_CPF_LEAK", Severity.HIGH, Category.HEADER_LEAK, "Potential Brazil CPF leaked in header '" + name + "': " + valDisplay);
                } else if (FR_NIR_PATTERN.matcher(value).find()) {
                    addFinding(findings, evidence, "PII_FR_NIR_LEAK", Severity.HIGH, Category.HEADER_LEAK, "Potential France SSN (NIR) leaked in header '" + name + "': " + valDisplay);
                }

                if (lowerName.contains("name") || lowerName.contains("user") || lowerName.contains("author") || lowerName.contains("email")) {
                    if (!lowerName.equals("user-agent") && !lowerName.equals("server-timing")
                            && !lowerName.contains("authorization")) {
                        addFinding(findings, evidence, "PII_HEADER_KEY_LEAK", Severity.LOW, Category.HEADER_LEAK,
                            "Header key suggests PII disclosure: " + name + " = " + valDisplay);
                    }
                }
            }

            // CWE-200: Financial Data
            if (checkCwe200Financial) {
                String valDisplay = redactPiiValues ? "[REDACTED]" : value;
                if (containsCardNumber(value)) {
                    addFinding(findings, evidence, "CREDIT_CARD_LEAK", Severity.HIGH, Category.HEADER_LEAK, "Potential Credit Card leaked in header '" + name + "': " + valDisplay);
                }
                if (IBAN_PATTERN.matcher(value).find()) {
                    addFinding(findings, evidence, "IBAN_LEAK", Severity.LOW, Category.HEADER_LEAK, "Potential IBAN leaked in header '" + name + "': " + valDisplay);
                }
            }

            // CWE-200: Backend Information / Stacktraces
            if (checkCwe200Backend) {
                if (BACKEND_INFO_PATTERN.matcher(value).find()) {
                    addFinding(findings, evidence, "BACKEND_INFO_LEAK", Severity.HIGH, Category.HEADER_LEAK, "Backend stacktrace or framework details leaked in header '" + name + "'");
                }
            }

            // CWE-200: Internal Infrastructure
            if (checkCwe200Infra) {
                if (!lowerName.equals("x-forwarded-for") && !lowerName.equals("x-real-ip")) {
                    if (CWE200_INTERNAL_IP_PATTERN.matcher(value).find()) {
                        addHostFinding(findings, evidence, "INTERNAL_IP_LEAK", Severity.MEDIUM, Category.HEADER_LEAK, "Internal IP (IPv4/IPv6) leaked in header '" + name + "': " + value, lowerName);
                    }
                }
                if (INTERNAL_DOMAIN_PATTERN.matcher(value).find()) {
                    addHostFinding(findings, evidence, "INTERNAL_DOMAIN_LEAK", Severity.MEDIUM, Category.HEADER_LEAK, "Internal domain/hostname leaked in header '" + name + "': " + value, lowerName);
                }
            }

            // Missing Security Headers tracking
            if (checkMissing) {
                if (lowerName.equals("strict-transport-security")) hasHsts = true;
                else if (lowerName.equals("content-security-policy")) {
                    hasCsp = true;
                    if (lowerValue.contains("frame-ancestors")) hasFrameAncestors = true;
                }
                else if (lowerName.equals("x-content-type-options") && lowerValue.trim().equals("nosniff")) hasXcto = true;
                else if (lowerName.equals("x-frame-options")) hasXfo = true;
                else if (lowerName.equals("referrer-policy")) hasRp = true;
                else if (lowerName.equals("permissions-policy")) hasPp = true;
            }
        }

        // Missing Security Headers check
        // Only on final (2xx) responses: redirects, 304s and error pages routinely come from a
        // different layer (CDN, LB) and would flag the host for headers the app does send.
        if (checkMissing && finalResponse) {
            // HSTS is ignored by browsers over plain HTTP, so it can't be "missing" there.
            if (!hasHsts && https) addHostFinding(findings, evidence, "MISSING_HSTS", Severity.LOW, Category.HEADER_MISSING, "Strict-Transport-Security header is missing", null);
            if (!hasXcto) addHostFinding(findings, evidence, "MISSING_XCTO", Severity.LOW, Category.HEADER_MISSING, "X-Content-Type-Options header is missing or not 'nosniff'", null);
            if (htmlDocument) {
                if (!hasCsp) addHostFinding(findings, evidence, "MISSING_CSP", Severity.LOW, Category.HEADER_MISSING, "Content-Security-Policy header is missing", null);
                // CSP frame-ancestors is the modern equivalent of X-Frame-Options.
                if (!hasXfo && !hasFrameAncestors) addHostFinding(findings, evidence, "MISSING_XFO", Severity.LOW, Category.HEADER_MISSING, "X-Frame-Options header (or CSP frame-ancestors) is missing", null);
                if (!hasRp) addHostFinding(findings, evidence, "MISSING_RP", Severity.LOW, Category.HEADER_MISSING, "Referrer-Policy header is missing", null);
                if (!hasPp) addHostFinding(findings, evidence, "MISSING_PP", Severity.LOW, Category.HEADER_MISSING, "Permissions-Policy header is missing", null);
            }
        }

        return findings;
    }

    /**
     * Re-check for the deterministic MISSING_* and COOKIE_MISSING_* finding types — same header-name/
     * value predicates as {@link #analyze}, so a re-check can't silently drift from the original
     * detection. Returns whether the originally-flagged-missing header or cookie flag is present
     * on {@code response} now; {@code null} if {@code findingType} isn't one of these deterministic
     * types (the PII/leak/version checks are heuristic pattern matches, not a clean presence check,
     * so they're deliberately not covered here). For a Set-Cookie flag, ORs across every Set-Cookie
     * header present — coarser than the original per-header check, which can flag one specific
     * cookie while another already has the flag; documented as a known imprecision by the caller.
     */
    public static Boolean isNowPresent(String findingType, HttpResponse response) {
        boolean hasHsts = false, hasCsp = false, hasXcto = false, hasXfo = false, hasRp = false, hasPp = false;
        Boolean cookieSecure = null, cookieHttpOnly = null, cookieSameSite = null;

        for (HttpHeader h : response.headers()) {
            String lowerName = h.name().toLowerCase();
            String lowerValue = h.value().toLowerCase();
            if (lowerName.equals("strict-transport-security")) hasHsts = true;
            else if (lowerName.equals("content-security-policy")) {
                hasCsp = true;
                if (lowerValue.contains("frame-ancestors")) hasXfo = true; // modern XFO equivalent
            }
            else if (lowerName.equals("x-content-type-options") && lowerValue.trim().equals("nosniff")) hasXcto = true;
            else if (lowerName.equals("x-frame-options")) hasXfo = true;
            else if (lowerName.equals("referrer-policy")) hasRp = true;
            else if (lowerName.equals("permissions-policy")) hasPp = true;
            else if (lowerName.equals("set-cookie")) {
                Set<String> attrs = cookieAttributes(h.value());
                cookieSecure = Boolean.TRUE.equals(cookieSecure) || attrs.contains("secure");
                cookieHttpOnly = Boolean.TRUE.equals(cookieHttpOnly) || attrs.contains("httponly");
                cookieSameSite = Boolean.TRUE.equals(cookieSameSite) || attrs.contains("samesite");
            }
        }

        return switch (findingType) {
            case "MISSING_HSTS" -> hasHsts;
            case "MISSING_CSP" -> hasCsp;
            case "MISSING_XCTO" -> hasXcto;
            case "MISSING_XFO" -> hasXfo;
            case "MISSING_RP" -> hasRp;
            case "MISSING_PP" -> hasPp;
            case "COOKIE_MISSING_SECURE" -> cookieSecure;
            case "COOKIE_MISSING_HTTPONLY" -> cookieHttpOnly;
            case "COOKIE_MISSING_SAMESITE" -> cookieSameSite;
            default -> null;
        };
    }

    /** Reused by validate_finding to re-check VERSION_DISCLOSURE without a second hand-copied
     *  pattern list — same header set and regex as the live check above. */
    public static boolean hasVersionDisclosure(HttpResponse response) {
        for (HttpHeader h : response.headers()) {
            String lowerName = h.name().toLowerCase();
            if (lowerName.equals("server") && VERSION_PATTERN.matcher(h.value()).find()) return true;
            if (lowerName.equals("x-powered-by") || lowerName.equals("x-aspnet-version")
                    || lowerName.equals("x-aspnetmvc-version") || lowerName.equals("x-generator")) return true;
        }
        return false;
    }

    /** Luhn checksum validation to filter credit-card-shaped digit runs before raising a finding. */
    private static boolean isValidLuhn(String candidate) {
        String digits = candidate.replaceAll("[^0-9]", "");
        if (digits.length() < 13 || digits.length() > 19) return false;

        int sum = 0;
        boolean alternate = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int n = digits.charAt(i) - '0';
            if (alternate) {
                n *= 2;
                if (n > 9) n -= 9;
            }
            sum += n;
            alternate = !alternate;
        }
        return sum % 10 == 0;
    }

    /** Lower-cased attribute names of a Set-Cookie value (everything after the name=value pair). */
    static Set<String> cookieAttributes(String setCookie) {
        Set<String> attrs = new HashSet<>();
        String[] parts = setCookie.split(";");
        for (int i = 1; i < parts.length; i++) {
            String attr = parts[i].trim();
            int eq = attr.indexOf('=');
            if (eq >= 0) attr = attr.substring(0, eq).trim();
            if (!attr.isEmpty()) attrs.add(attr.toLowerCase(Locale.ROOT));
        }
        return attrs;
    }

    static String cookieName(String setCookie) {
        int eq = setCookie.indexOf('=');
        int semi = setCookie.indexOf(';');
        int end = eq >= 0 && (semi < 0 || eq < semi) ? eq : (semi >= 0 ? semi : setCookie.length());
        return setCookie.substring(0, end).trim();
    }

    /** A Set-Cookie that clears the cookie (Max-Age<=0) carries no value worth protecting. */
    private static boolean isCookieDeletion(String setCookie) {
        for (String part : setCookie.split(";")) {
            String p = part.trim().toLowerCase(Locale.ROOT);
            if (p.startsWith("max-age=")) {
                try {
                    return Long.parseLong(p.substring("max-age=".length()).trim()) <= 0;
                } catch (NumberFormatException ignored) {
                    return false;
                }
            }
        }
        return false;
    }

    /** Luhn-valid digit run that also starts with a real card-network prefix — plain Luhn alone
     *  let 13-digit epoch-millisecond timestamps (common in headers) through ~10% of the time. */
    private static boolean containsCardNumber(String value) {
        var m = CREDIT_CARD_PATTERN.matcher(value);
        while (m.find()) {
            String digits = m.group().replaceAll("[^0-9]", "");
            if (hasCardNetworkPrefix(digits) && isValidLuhn(digits)) return true;
        }
        return false;
    }

    private static boolean hasCardNetworkPrefix(String d) {
        if (d.length() < 13) return false;
        int two = Integer.parseInt(d.substring(0, 2));
        int four = Integer.parseInt(d.substring(0, 4));
        return d.charAt(0) == '4'                                   // Visa
                || (two >= 51 && two <= 55) || (four >= 2221 && four <= 2720) // Mastercard
                || two == 34 || two == 37                           // Amex
                || d.startsWith("6011") || d.startsWith("65") || (four >= 6440 && four <= 6499) // Discover
                || (four >= 3528 && four <= 3589)                   // JCB
                || two == 36 || two == 38 || (four >= 3000 && four <= 3059); // Diners
    }

    /** {@code pattern} matches somewhere with a digit count in [minDigits, maxDigits] that passes Luhn. */
    private static boolean matchesWithLuhn(Pattern pattern, String value, int minDigits, int maxDigits) {
        var m = pattern.matcher(value);
        while (m.find()) {
            String digits = m.group().replaceAll("[^0-9]", "");
            if (digits.length() >= minDigits && digits.length() <= maxDigits && isValidLuhn(digits)) return true;
        }
        return false;
    }

    /**
     * Per-endpoint finding (e.g. PII in a header on one URL). Path drops the query string — a
     * cache-buster or pagination param otherwise turned one issue into one finding per URL.
     */
    private void addFinding(List<Finding> findings, HttpRequestResponse evidence, String type, Severity severity, Category category, String description) {
        String path = (evidence != null && evidence.request() != null) ? evidence.request().pathWithoutQuery() : "";
        Finding.Builder b = Finding.builder(name(), type)
                .description(description)
                .severity(severity)
                .category(category)
                .path(path)
                .evidence(evidence); // Can be null for passive scans
        String host = evidence != null ? Finding.hostScope(evidence.request()) : "";
        if (!host.isBlank()) b.meta(Finding.META_SCOPE, host);
        findings.add(b.build());
    }

    /**
     * Server-wide finding (missing header, version banner, cookie flag): recorded once per host
     * (and per {@code subject}, e.g. the header or cookie name, when several can co-exist)
     * instead of once per URL. Path shows the origin, since no single path "owns" the issue.
     */
    private void addHostFinding(List<Finding> findings, HttpRequestResponse evidence, String type, Severity severity,
                                Category category, String description, String subject) {
        var req = evidence != null ? evidence.request() : null;
        String host = Finding.hostScope(req);
        if (host.isBlank()) {
            addFinding(findings, evidence, type, severity, category, description);
            return;
        }
        var svc = req.httpService();
        boolean defaultPort = svc.port() == (svc.secure() ? 443 : 80);
        String origin = (svc.secure() ? "https://" : "http://") + host + (defaultPort ? "" : ":" + svc.port());
        findings.add(Finding.builder(name(), type)
                .description(description)
                .severity(severity)
                .category(category)
                .path(origin)
                .evidence(evidence)
                .meta(Finding.META_SCOPE, subject == null || subject.isBlank() ? host : host + " " + subject)
                .build());
    }
}
