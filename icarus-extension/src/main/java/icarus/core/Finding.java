package icarus.core;

import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import burp.api.montoya.http.message.HttpRequestResponse;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Immutable value object representing a single security finding.
 * Every ICARUS module produces these; the orchestrator routes them
 * to evidence capture, Repeater, Organizer, and audit issues.
 */
public final class Finding {

    private final String module;
    private final String type;
    private final String description;
    private final Severity severity;
    private final Category category;
    private final String path;
    private final HttpRequestResponse evidence;
    private final Map<String, String> metadata;

    private Finding(Builder builder) {
        this.module = builder.module;
        this.type = builder.type;
        this.description = builder.description;
        this.severity = builder.severity;
        this.category = builder.category;
        this.path = builder.path;
        this.evidence = builder.evidence;
        this.metadata = Collections.unmodifiableMap(new LinkedHashMap<>(builder.metadata));
    }

    public String module()        { return module; }
    public String type()          { return type; }
    public String description()   { return description; }
    public Severity severity()    { return severity; }
    public Category category()    { return category; }
    public String path()          { return path; }
    public HttpRequestResponse evidence() { return evidence; }
    public Map<String, String> metadata() { return metadata; }

    /**
     * Hash used for deduplication.
     */
    public String similarityHash() {
        return module + "|" + type + "|" + path;
    }

    /**
     * Compact label for Repeater tab names and log lines.
     */
    public String shortLabel() {
        return switch (type) {
            case "STRING_XSS"            -> "XSS";
            case "STRING_SQLI"           -> "SQLI";
            case "STRING_NOSQLI"         -> "NOSQL";
            case "STRING_PATH_TRAVERSAL" -> "TRAV";
            case "STRING_FORMAT"         -> "FMT";
            case "STRING_UNICODE"        -> "UNICODE";
            case "NULL_VALUE"            -> "NULL";
            case "FIELD_REMOVED"         -> "REMOVE";
            case "TYPE_EMPTY_OBJECT"     -> "OBJ0";
            case "TYPE_EMPTY_ARRAY"      -> "ARR0";
            case "EMPTY_STRING"          -> "EMPTY";
            case "STRING_LONG"           -> "LONG";
            case "NUMBER_ZERO"           -> "ZERO";
            case "NUMBER_NEGATIVE"       -> "NEG";
            case "NUMBER_OVERFLOW"       -> "MAX";
            case "NUMBER_FLOAT"          -> "FLOAT";
            case "BOOLEAN_FLIP"          -> "FLIP";
            case "TYPE_STRING"           -> "STR";
            case "TYPE_STRING_NUMERIC"   -> "NUMSTR";
            case "TYPE_NUMBER"           -> "NUM";
            case "TYPE_BOOLEAN"          -> "BOOL";
            default -> type.length() <= 8 ? type : type.substring(0, 8);
        };
    }

    @Override
    public String toString() {
        return "[%s] %s | %s | %s | %s".formatted(severity, module, type, path, description);
    }

    // ── Builder ─────────────────────────────────────────────────

    public static Builder builder(String module, String type) {
        return new Builder(module, type);
    }

    public static final class Builder {
        private final String module;
        private final String type;
        private String description = "";
        private Severity severity = Severity.INFO;
        private Category category = Category.MANUAL;
        private String path = "";
        private HttpRequestResponse evidence;
        private final Map<String, String> metadata = new LinkedHashMap<>();

        private Builder(String module, String type) {
            this.module = module;
            this.type = type;
        }

        public Builder description(String v) { this.description = v; return this; }
        public Builder severity(Severity v)   { this.severity = v;   return this; }
        public Builder category(Category v)   { this.category = v;   return this; }
        public Builder path(String v)         { this.path = v;       return this; }
        public Builder evidence(HttpRequestResponse v) { this.evidence = v; return this; }
        public Builder meta(String key, String value)  { this.metadata.put(key, value); return this; }

        public Finding build() {
            return new Finding(this);
        }
    }
}
