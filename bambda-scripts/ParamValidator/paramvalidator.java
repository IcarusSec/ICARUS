// ============================================================
// JSON Parameter Validation Tester
// Burp Suite Custom Action
//
// Authors: Victor Lima | Adan Ferreira
//
// Description:
// JSON Parameter validation for API security testing.
//
// Version: 0.1.2
// Last update: 2026-07-16
// ============================================================

// ============================================================
// ================= BASIC USAGE =================
// ------------------------------------------------------------
// Paste this script in: Repeater > Custom actions > New > Blank
// Right-click on a request with a JSON body and run.
//
// Key features:
//  - Mutations are categorized (STRUCTURAL / TYPE_CONFUSION /
//    BOUNDARY / INJECTION) and each category can be turned on or
//    off below, to control volume/execution time.
//
//  - 4xx, 5xx responses, redirects and connection failures
//    are not sent to Repeater; they are only logged.
//  - Findings above the tab limit are sent to Organizer.
//  - Path rules allow inclusion, exclusion and specific exceptions
//    per test or category, with wildcard support.
//
// No external dependencies: custom JSON parser/serializer,
// since Custom Actions don't allow adding libraries.
// ============================================================

// ================= USER CONFIGURATION =================
//
// Change only this section to customize execution.

// ---------- Main categories ----------
var TEST_STRUCTURAL = true;       // null, removal, empty object and empty array
var TEST_TYPE_CONFUSION = true;   // swap between string, number and boolean
var TEST_BOUNDARY = true;         // empty, negatives, overflow and long strings
var TEST_INJECTION = true;        // XSS, SQLi, NoSQLi, traversal etc.

// ---------- Individual structural tests ----------
var TEST_NULL_VALUE = true;
var TEST_FIELD_REMOVAL = true;
var TEST_EMPTY_OBJECT = true;
var TEST_EMPTY_ARRAY = true;

// ---------- Individual boundary tests ----------
var TEST_EMPTY_STRING = true;
var TEST_LONG_STRING = true;
var TEST_NUMBER_ZERO = true;
var TEST_NUMBER_NEGATIVE = true;
var TEST_NUMBER_OVERFLOW = true;
var TEST_INTEGER_AS_FLOAT = true;
var TEST_BOOLEAN_FLIP = true;

// ---------- Individual injection tests ----------
var TEST_SQLI = true;
var TEST_SQLI_TIME = true;
var TEST_XSS = true;
var TEST_PATH_TRAVERSAL = true;
var TEST_NOSQLI = true;
var TEST_FORMAT_STRING = true;
var TEST_UNICODE = true;

// ---------- Type confusion tests ----------
var TEST_STRING_AS_NUMBER = true;
var TEST_STRING_AS_BOOLEAN = true;
var TEST_NUMBER_AS_STRING = true;
var TEST_NUMBER_AS_NUMERIC_STRING = true;
var TEST_BOOLEAN_AS_STRING = true;
var TEST_BOOLEAN_AS_NUMBER = true;

// ---------- Rules by JSON path ----------
//
// Path syntax:
//   $.user.email          exact path
//   $.users[*].email      any array index
//   $.metadata.*          any direct child
//   $.metadata.**         any descendant
//
// INCLUSION:
//   - Empty list: all paths can be tested.
//   - Populated list: only paths that match some pattern.
//
// EXCLUSION:
//   - Paths matching here will receive no mutations.
//   - Exclusion has priority over inclusion.
//
// EXCEPTIONS:
//   - Prevent only a single test or category on a given path.
//   - Format: "PATH_PATTERN::RULE"
//   - Accepted rules:
//       ALL
//       CATEGORY:STRUCTURAL
//       CATEGORY:TYPE_CONFUSION
//       CATEGORY:BOUNDARY
//       CATEGORY:INJECTION
//       or the exact test name, like STRING_XSS or NULL_VALUE.

List<String> INCLUDE_PATH_PATTERNS = List.of(
        // "$.user.**",
        // "$.items[*].**"
);

// Examples:
// "$.chosen_discount"    -> excludes only this path
// "$.chosen_discount.**" -> excludes the path and all descendants
// "$.items[*].internalId" -> excludes the field in any array item
List<String> EXCLUDE_PATH_PATTERNS = List.of(
        //"$.chosen_discount.**"
        // "$.audit.**"
);

List<String> PATH_TEST_EXCEPTIONS = List.of(
        // "$.profile.nickname::NULL_VALUE",
        // "$.description::CATEGORY:STRUCTURAL",
        // "$.items[*].internalId::ALL"
);

// Shows skipped paths in the log.
var LOG_PATH_RULE_SKIPS = true;

// Shows all JSON paths discovered before mutations.
// Useful to confirm the exact field name during configuration.
var LOG_DISCOVERED_JSON_PATHS = false;

// Shows each configured inclusion/exclusion pattern and how many
// discovered JSON paths matched it.
var LOG_PATH_RULE_DIAGNOSTICS = true;

// ---------- Editable payloads ----------
var PAYLOAD_SQLI = "' OR '1'='1";
var PAYLOAD_SQLI_TIME = "'; WAITFOR DELAY '0:0:10'--"; // 10 second delay
var PAYLOAD_SQLI_TIME_DELAY_MS = 10000;
var PAYLOAD_XSS = "<script>alert(1)</script>";
var PAYLOAD_PATH_TRAVERSAL = "../../../../etc/passwd";
var PAYLOAD_NOSQLI = "{\"$ne\": null}";
var PAYLOAD_FORMAT_STRING = "%s%x%n";
var PAYLOAD_UNICODE = "\u202Etest\uD83D\uDE00";

var PAYLOAD_NUMBER_SQLI_MATH = "1/0";
var PAYLOAD_NUMBER_SQLI_TIME = "1-(WAITFOR DELAY '0:0:10')";

var PAYLOAD_NON_NUMERIC_STRING = "abc";
var PAYLOAD_NUMERIC_STRING = "123";
var PAYLOAD_BOOLEAN_STRING = "true";
var PAYLOAD_LONG_STRING_CHARACTER = "A";

// ---------- Boundary values ----------
var LONG_STRING_LENGTH = 10000;
var NEGATIVE_INTEGER_VALUE = -1L;
var NEGATIVE_DECIMAL_VALUE = -1.5;
var INTEGER_AS_FLOAT_VALUE = 1.5;
var STRING_AS_NUMBER_VALUE = 0L;
var BOOLEAN_AS_NUMBER_VALUE = 1L;

// ---------- Finding criteria ----------
// Any response within this range is considered acceptance.
var FINDING_STATUS_MIN = 200;
var FINDING_STATUS_MAX = 299;

// Requires that the original request also returns a status in this range.
var REQUIRE_SUCCESSFUL_BASELINE = true;
var BASELINE_STATUS_MIN = 200;
var BASELINE_STATUS_MAX = 299;

// ---------- Volume and destinations ----------
var MAX_MUTATIONS = 60;
var MAX_REPEATER_SENDS = 10;

// When the Repeater limit is reached, send the excess to Organizer.
var SEND_EXCESS_TO_ORGANIZER = true;

// ---------- Advanced Detection ----------
// If the mutation response length is exactly the same as the baseline length, ignore it as false positive.
// Set to false by default because identical responses (e.g., 201 Created with 0 bytes) often indicate successful payload processing!
var FILTER_EXACT_BASELINE_MATCH = false;

// If the XSS payload is reflected exactly in the response, flag it regardless of status code.
var CHECK_XSS_REFLECTION = true;

// ---------- Audit Issues ----------
// Generates native Burp Scanner issues in the Dashboard for discovered vulnerabilities.
var CREATE_AUDIT_ISSUES = true;

// ---------- Repeater organization ----------
//
// The Montoya API used by Custom Actions does not allow creating or selecting
// tab groups via code. For findings to enter a group:
// 1. create the group manually in Repeater;
// 2. select this group in Settings > Tools > Repeater > Default tab group.
//
// This variable identifies in the log which group should be configured.
var REPEATER_GROUP_NAME = "JSON Validation";

// Short prefix used in tab names.
var REPEATER_TAB_PREFIX = "IV";

// Maximum character limit in the full tab name.
var REPEATER_TAB_NAME_MAX_LENGTH = 28;

// How many final JSON path components appear in the name.
var REPEATER_TAB_PATH_COMPONENTS = 2;

// Adds a short counter at the end to prevent duplicate names.
var REPEATER_TAB_INCLUDE_COUNTER = true;

// Displays guidance about the default group at the beginning.
var LOG_REPEATER_GROUP_GUIDANCE = true;

// ---------- Logs ----------
var LOG_REJECTED_PAYLOADS = true;
var LOG_SERVER_ERRORS = true;
var LOG_REDIRECTS = true;
var LOG_OTHER_STATUS = true;

// ============================================================

var request = requestResponse.request();

if (LOG_REPEATER_GROUP_GUIDANCE) {
    logging.logToOutput(
            "[REPEATER] To automatically group tabs for this action, "
                    + "create the group '"
                    + REPEATER_GROUP_NAME
                    + "' and select it in Settings > Tools > Repeater "
                    + "> Default tab group."
    );
}

var contentType = request.headerValue("Content-Type");
var originalBody = request.bodyToString();

var looksLikeJson = (contentType != null && contentType.toLowerCase().contains("json"))
        || (originalBody != null && (originalBody.trim().startsWith("{") || originalBody.trim().startsWith("[")));

if (originalBody == null || originalBody.isBlank() || !looksLikeJson) {
    logging.logToOutput("Request body is empty or does not seem to be JSON - nothing to test.");
    return;
}

// ---------- Minimal JSON parser/serializer ----------
class Json {

    static Object parse(String text) {
        var p = new int[]{0};
        skipWs(text, p);
        return parseValue(text, p);
    }

    static void skipWs(String s, int[] p) {
        while (p[0] < s.length() && Character.isWhitespace(s.charAt(p[0]))) p[0]++;
    }

    static Object parseValue(String s, int[] p) {
        skipWs(s, p);
        char c = s.charAt(p[0]);
        if (c == '{') return parseObject(s, p);
        if (c == '[') return parseArray(s, p);
        if (c == '"') return parseString(s, p);
        if (s.startsWith("true", p[0])) { p[0] += 4; return Boolean.TRUE; }
        if (s.startsWith("false", p[0])) { p[0] += 5; return Boolean.FALSE; }
        if (s.startsWith("null", p[0])) { p[0] += 4; return null; }
        return parseNumber(s, p);
    }

    static LinkedHashMap<String, Object> parseObject(String s, int[] p) {
        var map = new LinkedHashMap<String, Object>();
        p[0]++; skipWs(s, p);
        if (s.charAt(p[0]) == '}') { p[0]++; return map; }
        while (true) {
            skipWs(s, p);
            var key = parseString(s, p);
            skipWs(s, p);
            p[0]++; // ':'
            var value = parseValue(s, p);
            map.put(key, value);
            skipWs(s, p);
            char c = s.charAt(p[0]);
            p[0]++;
            if (c == '}') break;
        }
        return map;
    }

    static ArrayList<Object> parseArray(String s, int[] p) {
        var list = new ArrayList<Object>();
        p[0]++; skipWs(s, p);
        if (s.charAt(p[0]) == ']') { p[0]++; return list; }
        while (true) {
            var value = parseValue(s, p);
            list.add(value);
            skipWs(s, p);
            char c = s.charAt(p[0]);
            p[0]++;
            if (c == ']') break;
        }
        return list;
    }

    static String parseString(String s, int[] p) {
        var sb = new StringBuilder();
        p[0]++; // pula aspas de abertura
        while (s.charAt(p[0]) != '"') {
            char c = s.charAt(p[0]);
            if (c == '\\') {
                p[0]++;
                char esc = s.charAt(p[0]);
                switch (esc) {
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case 'r' -> sb.append('\r');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    case 'u' -> {
                        var hex = s.substring(p[0] + 1, p[0] + 5);
                        sb.append((char) Integer.parseInt(hex, 16));
                        p[0] += 4;
                    }
                    default -> sb.append(esc);
                }
            } else {
                sb.append(c);
            }
            p[0]++;
        }
        p[0]++; // pula aspas de fechamento
        return sb.toString();
    }

    static Object parseNumber(String s, int[] p) {
        int start = p[0];
        while (p[0] < s.length() && "-+.eE0123456789".indexOf(s.charAt(p[0])) >= 0) p[0]++;
        var num = s.substring(start, p[0]);
        if (num.contains(".") || num.contains("e") || num.contains("E")) return Double.parseDouble(num);
        try { return Long.parseLong(num); } catch (Exception e) { return Double.parseDouble(num); }
    }

    static String write(Object o) {
        if (o == null) return "null";
        if (o instanceof String str) return "\"" + escape(str) + "\"";
        if (o instanceof Boolean || o instanceof Long || o instanceof Integer || o instanceof Double) return String.valueOf(o);
        if (o instanceof Map<?, ?> map) {
            var sb = new StringBuilder("{");
            var first = true;
            for (var e : map.entrySet()) {
                if (!first) sb.append(",");
                first = false;
                sb.append("\"").append(escape((String) e.getKey())).append("\":").append(write(e.getValue()));
            }
            return sb.append("}").toString();
        }
        if (o instanceof List<?> list) {
            var sb = new StringBuilder("[");
            var first = true;
            for (var v : list) {
                if (!first) sb.append(",");
                first = false;
                sb.append(write(v));
            }
            return sb.append("]").toString();
        }
        return "\"" + escape(String.valueOf(o)) + "\"";
    }

    static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }
}

enum Category { STRUCTURAL, TYPE_CONFUSION, BOUNDARY, INJECTION }
enum Severity { NONE, LOW, MEDIUM, HIGH }

record MutationSpec(String type, String description, Object value, boolean remove, Category category) {}
record Mutation(String path, String type, String description, Category category, String body) {}

// ---------- Nomes curtos para abas do Repeater ----------
class RepeaterNames {

    static String testLabel(String type) {
        if (type == null) {
            return "TEST";
        }

        return switch (type) {
            case "STRING_XSS" -> "XSS";
            case "STRING_SQLI" -> "SQLI";
            case "STRING_NOSQLI" -> "NOSQL";
            case "STRING_PATH_TRAVERSAL" -> "TRAV";
            case "STRING_FORMAT" -> "FMT";
            case "STRING_UNICODE" -> "UNICODE";

            case "NULL_VALUE" -> "NULL";
            case "FIELD_REMOVED" -> "REMOVE";
            case "TYPE_EMPTY_OBJECT" -> "OBJ0";
            case "TYPE_EMPTY_ARRAY" -> "ARR0";

            case "EMPTY_STRING" -> "EMPTY";
            case "STRING_LONG" -> "LONG";
            case "NUMBER_ZERO" -> "ZERO";
            case "NUMBER_NEGATIVE" -> "NEG";
            case "NUMBER_OVERFLOW" -> "MAX";
            case "NUMBER_FLOAT" -> "FLOAT";
            case "BOOLEAN_FLIP" -> "FLIP";

            case "TYPE_STRING" -> "STR";
            case "TYPE_STRING_NUMERIC" -> "NUMSTR";
            case "TYPE_NUMBER" -> "NUM";
            case "TYPE_BOOLEAN" -> "BOOL";

            default -> abbreviate(type, 8);
        };
    }

    static String shortPath(String path, int componentLimit) {
        if (path == null || path.isBlank() || "$".equals(path)) {
            return "root";
        }

        var normalized = path;

        if (normalized.startsWith("$.")) {
            normalized = normalized.substring(2);
        } else if (normalized.startsWith("$")) {
            normalized = normalized.substring(1);
        }

        // Todos os indices sao mostrados como [] para manter o titulo curto.
        normalized = normalized.replaceAll("\\\\[\\\\d+\\\\]", "[]");

        var components = normalized.split("\\\\.");
        var start = Math.max(0, components.length - Math.max(1, componentLimit));
        var output = new StringBuilder();

        for (int index = start; index < components.length; index++) {
            if (output.length() > 0) {
                output.append(".");
            }

            output.append(components[index]);
        }

        var sanitized = output.toString()
                .replaceAll("[^a-zA-Z0-9._\\\\[\\\\]-]", "_");

        return sanitized.isBlank()
                ? "root"
                : sanitized;
    }

    static String create(
            String prefix,
            String type,
            String path,
            int findingNumber,
            int maxLength,
            int pathComponents,
            boolean includeCounter
    ) {
        var safePrefix = prefix == null || prefix.isBlank()
                ? "IV"
                : prefix.trim();

        var label = testLabel(type);
        var compactPath = shortPath(path, pathComponents);
        var counter = includeCounter
                ? "-" + String.format("%02d", findingNumber)
                : "";

        var fixedLength =
                safePrefix.length()
                        + 1
                        + label.length()
                        + 1
                        + counter.length();

        var availablePathLength = Math.max(
                4,
                maxLength - fixedLength
        );

        compactPath = abbreviate(
                compactPath,
                availablePathLength
        );

        var result =
                safePrefix
                        + "-"
                        + label
                        + "-"
                        + compactPath
                        + counter;

        return abbreviate(
                result,
                Math.max(12, maxLength)
        );
    }

    static String abbreviate(String value, int maxLength) {
        if (value == null) {
            return "";
        }

        if (value.length() <= maxLength) {
            return value;
        }

        if (maxLength <= 3) {
            return value.substring(0, maxLength);
        }

        return value.substring(0, maxLength - 3) + "...";
    }
}

// ---------- Regras de inclusao, exclusao e excecao por caminho ----------
class PathRules {

    boolean isIncluded(String path) {
        if (INCLUDE_PATH_PATTERNS == null || INCLUDE_PATH_PATTERNS.isEmpty()) {
            return true;
        }

        return matchesAny(path, INCLUDE_PATH_PATTERNS);
    }

    boolean isExcluded(String path) {
        return EXCLUDE_PATH_PATTERNS != null
                && matchesAny(path, EXCLUDE_PATH_PATTERNS);
    }

    boolean isException(String path, MutationSpec spec) {
        if (PATH_TEST_EXCEPTIONS == null || PATH_TEST_EXCEPTIONS.isEmpty()) {
            return false;
        }

        for (String rawRule : PATH_TEST_EXCEPTIONS) {
            if (rawRule == null || rawRule.isBlank()) {
                continue;
            }

            var separatorIndex = rawRule.lastIndexOf("::");

            if (separatorIndex <= 0 || separatorIndex >= rawRule.length() - 2) {
                logging.logToError(
                        "Regra de excecao invalida: "
                                + rawRule
                                + " | use CAMINHO::REGRA"
                );
                continue;
            }

            var pathPattern = rawRule.substring(0, separatorIndex).trim();
            var exceptionRule = rawRule.substring(separatorIndex + 2).trim();

            if (!matches(path, pathPattern)) {
                continue;
            }

            if (exceptionRule.equalsIgnoreCase("ALL")) {
                return true;
            }

            if (exceptionRule.regionMatches(
                    true,
                    0,
                    "CATEGORY:",
                    0,
                    "CATEGORY:".length()
            )) {
                var categoryName = exceptionRule
                        .substring("CATEGORY:".length())
                        .trim();

                if (spec.category().name().equalsIgnoreCase(categoryName)) {
                    return true;
                }

                continue;
            }

            if (spec.type().equalsIgnoreCase(exceptionRule)) {
                return true;
            }
        }

        return false;
    }

    boolean matchesAny(String path, List<String> patterns) {
        for (String pattern : patterns) {
            if (pattern != null
                    && !pattern.isBlank()
                    && matches(path, pattern.trim())) {
                return true;
            }
        }

        return false;
    }

    boolean matches(String path, String pattern) {
        if (path == null || pattern == null || pattern.isBlank()) {
            return false;
        }

        var normalizedPattern = pattern.trim();

        /*
         * Regra de arvore:
         *
         *   $.chosen_discount.**
         *
         * deve casar tanto com o proprio campo:
         *
         *   $.chosen_discount
         *
         * quanto com qualquer descendente:
         *
         *   $.chosen_discount.value
         *   $.chosen_discount.items[0].id
         *
         * Na implementacao anterior, o ponto antes de ** era obrigatorio,
         * portanto um campo escalar nunca era excluido.
         */
        if (normalizedPattern.endsWith(".**")) {
            var basePattern = normalizedPattern.substring(
                    0,
                    normalizedPattern.length() - 3
            );

            return matches(path, basePattern)
                    || matches(path, basePattern + ".*")
                    || pathMatchesDescendantPattern(path, basePattern);
        }

        var regex = buildRegex(normalizedPattern);
        return path.matches(regex);
    }

    boolean pathMatchesDescendantPattern(
            String path,
            String basePattern
    ) {
        var baseRegex = buildRegex(basePattern);

        // Remove o marcador de fim para permitir "." ou "[" depois da base.
        if (baseRegex.endsWith("$")) {
            baseRegex = baseRegex.substring(
                    0,
                    baseRegex.length() - 1
            );
        }

        return path.matches(
                baseRegex + "(?:\\..+|\\[\\d+\\].*)$"
        );
    }

    int countMatches(
            List<List<Object>> discoveredPaths,
            String pattern
    ) {
        if (
                discoveredPaths == null
                        || pattern == null
                        || pattern.isBlank()
        ) {
            return 0;
        }

        var count = 0;

        for (List<Object> discoveredPath : discoveredPaths) {
            /*
             * PathRules is declared before Paths in this Bambda script.
             * Local classes cannot reference a class declared later in the
             * same block, so build the JSONPath locally for diagnostics.
             */
            var pathBuilder = new StringBuilder("$");

            for (Object pathPart : discoveredPath) {
                if (pathPart instanceof String propertyName) {
                    pathBuilder.append(".").append(propertyName);
                } else {
                    pathBuilder
                            .append("[")
                            .append(pathPart)
                            .append("]");
                }
            }

            var pathString = pathBuilder.toString();

            if (matches(pathString, pattern.trim())) {
                count++;
            }
        }

        return count;
    }

    String buildRegex(String pattern) {
        var regex = new StringBuilder("^");

        for (int index = 0; index < pattern.length();) {
            if (pattern.startsWith("[*]", index)) {
                regex.append("\\[\\d+\\]");
                index += 3;
                continue;
            }

            if (pattern.startsWith("**", index)) {
                regex.append(".*");
                index += 2;
                continue;
            }

            var current = pattern.charAt(index);

            if (current == '*') {
                regex.append("[^.\\[]+");
                index++;
                continue;
            }

            if ("\\\\.^$|?+(){}[]".indexOf(current) >= 0) {
                /*
                 * Append exactly one regex escape character.
                 *
                 * The previous implementation appended two backslashes,
                 * so exact JSON paths containing '$', '.' or brackets
                 * did not match the generated regular expression.
                 */
                regex.append("\\");
            }

            regex.append(current);
            index++;
        }

        regex.append("$");
        return regex.toString();
    }
}

// ---------- Navegacao e geracao de mutacoes ----------
class Paths {

    static List<List<Object>> collect(Object root) {
        var out = new ArrayList<List<Object>>();
        walk(root, new ArrayList<>(), out);
        return out;
    }

    static void walk(Object node, List<Object> current, List<List<Object>> out) {
        if (node instanceof Map<?, ?> map) {
            for (var key : map.keySet()) {
                var childPath = new ArrayList<Object>(current);
                childPath.add(key);
                out.add(childPath);
                var value = map.get(key);
                if (value instanceof Map || value instanceof List) walk(value, childPath, out);
            }
        } else if (node instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) {
                var childPath = new ArrayList<Object>(current);
                childPath.add(i);
                out.add(childPath);
                var value = list.get(i);
                if (value instanceof Map || value instanceof List) walk(value, childPath, out);
            }
        }
    }

    static Object getAt(Object root, List<Object> path) {
        Object current = root;
        for (var key : path) {
            if (current instanceof Map<?, ?> m && key instanceof String s) current = m.get(s);
            else if (current instanceof List<?> l && key instanceof Integer idx) current = l.get(idx);
            else return null;
        }
        return current;
    }

    @SuppressWarnings("unchecked")
    static boolean applyAt(Object root, List<Object> path, MutationSpec spec) {
        Object parent = root;
        for (int i = 0; i < path.size() - 1; i++) {
            var key = path.get(i);
            if (parent instanceof Map<?, ?> m && key instanceof String s) parent = m.get(s);
            else if (parent instanceof List<?> l && key instanceof Integer idx) parent = l.get(idx);
            else return false;
        }
        var lastKey = path.get(path.size() - 1);
        if (spec.remove()) {
            if (parent instanceof Map<?, ?> m && lastKey instanceof String s) {
                ((Map<Object, Object>) m).remove(s);
                return true;
            }
            return false; // remocao de elementos de array nao suportada (evita bagunçar indices)
        }
        if (parent instanceof Map<?, ?> m && lastKey instanceof String s) {
            ((Map<Object, Object>) m).put(s, spec.value());
            return true;
        } else if (parent instanceof List<?> l && lastKey instanceof Integer idx) {
            ((List<Object>) l).set(idx, spec.value());
            return true;
        }
        return false;
    }

    static String pathToString(List<Object> path) {
        var sb = new StringBuilder("$");
        for (var p : path) {
            if (p instanceof String s) sb.append(".").append(s);
            else sb.append("[").append(p).append("]");
        }
        return sb.toString();
    }

    List<MutationSpec> specsFor(Object value) {
        var specs = new ArrayList<MutationSpec>();

        if (TEST_STRUCTURAL) {
            if (TEST_NULL_VALUE) {
                specs.add(new MutationSpec(
                        "NULL_VALUE",
                        "Value replaced by null",
                        null,
                        false,
                        Category.STRUCTURAL
                ));
            }

            if (TEST_FIELD_REMOVAL) {
                specs.add(new MutationSpec(
                        "FIELD_REMOVED",
                        "Field removed from body",
                        null,
                        true,
                        Category.STRUCTURAL
                ));
            }

            if (TEST_EMPTY_OBJECT) {
                specs.add(new MutationSpec(
                        "TYPE_EMPTY_OBJECT",
                        "Value replaced by empty object {}",
                        new LinkedHashMap<String, Object>(),
                        false,
                        Category.STRUCTURAL
                ));
            }

            if (TEST_EMPTY_ARRAY) {
                specs.add(new MutationSpec(
                        "TYPE_EMPTY_ARRAY",
                        "Value replaced by empty array []",
                        new ArrayList<Object>(),
                        false,
                        Category.STRUCTURAL
                ));
            }
        }

        if (value instanceof String) {
            if (TEST_BOUNDARY) {
                if (TEST_EMPTY_STRING) {
                    specs.add(new MutationSpec(
                            "EMPTY_STRING",
                            "Empty string",
                            "",
                            false,
                            Category.BOUNDARY
                    ));
                }

                if (TEST_LONG_STRING) {
                    specs.add(new MutationSpec(
                            "STRING_LONG",
                            "Very long string (" + LONG_STRING_LENGTH + " chars)",
                            PAYLOAD_LONG_STRING_CHARACTER.repeat(LONG_STRING_LENGTH),
                            false,
                            Category.BOUNDARY
                    ));
                }
            }

            if (TEST_INJECTION) {
                if (TEST_SQLI) {
                    specs.add(new MutationSpec(
                            "STRING_SQLI",
                            "SQL Injection payload",
                            PAYLOAD_SQLI,
                            false,
                            Category.INJECTION
                    ));
                }

                if (TEST_SQLI_TIME) {
                    specs.add(new MutationSpec(
                            "STRING_SQLI_TIME",
                            "Time-based SQL Injection payload",
                            PAYLOAD_SQLI_TIME,
                            false,
                            Category.INJECTION
                    ));
                }

                if (TEST_XSS) {
                    specs.add(new MutationSpec(
                            "STRING_XSS",
                            "XSS payload",
                            PAYLOAD_XSS,
                            false,
                            Category.INJECTION
                    ));
                }

                if (TEST_PATH_TRAVERSAL) {
                    specs.add(new MutationSpec(
                            "STRING_PATH_TRAVERSAL",
                            "Path Traversal payload",
                            PAYLOAD_PATH_TRAVERSAL,
                            false,
                            Category.INJECTION
                    ));
                }

                if (TEST_NOSQLI) {
                    specs.add(new MutationSpec(
                            "STRING_NOSQLI",
                            "NoSQL Injection payload (string)",
                            PAYLOAD_NOSQLI,
                            false,
                            Category.INJECTION
                    ));
                }

                if (TEST_FORMAT_STRING) {
                    specs.add(new MutationSpec(
                            "STRING_FORMAT",
                            "Format string payload",
                            PAYLOAD_FORMAT_STRING,
                            false,
                            Category.INJECTION
                    ));
                }

                if (TEST_UNICODE) {
                    specs.add(new MutationSpec(
                            "STRING_UNICODE",
                            "Payload unicode / RTL override",
                            PAYLOAD_UNICODE,
                            false,
                            Category.INJECTION
                    ));
                }
            }

            if (TEST_TYPE_CONFUSION) {
                if (TEST_STRING_AS_NUMBER) {
                    specs.add(new MutationSpec(
                            "TYPE_NUMBER",
                            "String replaced by number",
                            STRING_AS_NUMBER_VALUE,
                            false,
                            Category.TYPE_CONFUSION
                    ));
                }

                if (TEST_STRING_AS_BOOLEAN) {
                    specs.add(new MutationSpec(
                            "TYPE_BOOLEAN",
                            "String replaced by boolean",
                            Boolean.TRUE,
                            false,
                            Category.TYPE_CONFUSION
                    ));
                }
            }
        } else if (value instanceof Long || value instanceof Integer) {
            if (TEST_BOUNDARY) {
                if (TEST_NUMBER_ZERO) {
                    specs.add(new MutationSpec(
                            "NUMBER_ZERO",
                            "Zero value",
                            0L,
                            false,
                            Category.BOUNDARY
                    ));
                }

                if (TEST_NUMBER_NEGATIVE) {
                    specs.add(new MutationSpec(
                            "NUMBER_NEGATIVE",
                            "Negative value",
                            NEGATIVE_INTEGER_VALUE,
                            false,
                            Category.BOUNDARY
                    ));
                }

                if (TEST_NUMBER_OVERFLOW) {
                    specs.add(new MutationSpec(
                            "NUMBER_OVERFLOW",
                            "Overflow (Long.MAX_VALUE)",
                            Long.MAX_VALUE,
                            false,
                            Category.BOUNDARY
                    ));
                }

                if (TEST_INTEGER_AS_FLOAT) {
                    specs.add(new MutationSpec(
                            "NUMBER_FLOAT",
                            "Float where integer was expected",
                            INTEGER_AS_FLOAT_VALUE,
                            false,
                            Category.BOUNDARY
                    ));
                }
            }

            if (TEST_TYPE_CONFUSION) {
                if (TEST_NUMBER_AS_STRING) {
                    specs.add(new MutationSpec(
                            "TYPE_STRING",
                            "Number replaced by non-numeric string",
                            PAYLOAD_NON_NUMERIC_STRING,
                            false,
                            Category.TYPE_CONFUSION
                    ));
                }

                if (TEST_NUMBER_AS_NUMERIC_STRING) {
                    specs.add(new MutationSpec(
                            "TYPE_STRING_NUMERIC",
                            "Number replaced by numeric string",
                            PAYLOAD_NUMERIC_STRING,
                            false,
                            Category.TYPE_CONFUSION
                    ));
                }
            }
        } else if (value instanceof Double) {
            if (TEST_BOUNDARY) {
                if (TEST_NUMBER_ZERO) {
                    specs.add(new MutationSpec(
                            "NUMBER_ZERO",
                            "Zero value",
                            0.0,
                            false,
                            Category.BOUNDARY
                    ));
                }

                if (TEST_NUMBER_NEGATIVE) {
                    specs.add(new MutationSpec(
                            "NUMBER_NEGATIVE",
                            "Negative value",
                            NEGATIVE_DECIMAL_VALUE,
                            false,
                            Category.BOUNDARY
                    ));
                }
            }

            if (TEST_INJECTION) {
                if (TEST_SQLI) {
                    specs.add(new MutationSpec(
                            "NUMBER_SQLI_MATH",
                            "Mathematical SQL Injection payload (Number context)",
                            PAYLOAD_NUMBER_SQLI_MATH,
                            false,
                            Category.INJECTION
                    ));
                }
                
                if (TEST_SQLI_TIME) {
                    specs.add(new MutationSpec(
                            "STRING_SQLI_TIME", // Using same type for detection
                            "Time-based SQL Injection payload (Number context)",
                            PAYLOAD_NUMBER_SQLI_TIME,
                            false,
                            Category.INJECTION
                    ));
                }
            }

            if (TEST_TYPE_CONFUSION && TEST_NUMBER_AS_STRING) {
                specs.add(new MutationSpec(
                        "TYPE_STRING",
                        "Number replaced by string",
                        PAYLOAD_NON_NUMERIC_STRING,
                        false,
                        Category.TYPE_CONFUSION
                ));
            }
        } else if (value instanceof Boolean b) {
            if (TEST_BOUNDARY && TEST_BOOLEAN_FLIP) {
                specs.add(new MutationSpec(
                        "BOOLEAN_FLIP",
                        "Boolean flipped",
                        !b,
                        false,
                        Category.BOUNDARY
                ));
            }

            if (TEST_TYPE_CONFUSION) {
                if (TEST_BOOLEAN_AS_STRING) {
                    specs.add(new MutationSpec(
                            "TYPE_STRING",
                            "Boolean replaced by string",
                            PAYLOAD_BOOLEAN_STRING,
                            false,
                            Category.TYPE_CONFUSION
                    ));
                }

                if (TEST_BOOLEAN_AS_NUMBER) {
                    specs.add(new MutationSpec(
                            "TYPE_NUMBER",
                            "Boolean replaced by number",
                            BOOLEAN_AS_NUMBER_VALUE,
                            false,
                            Category.TYPE_CONFUSION
                    ));
                }
            }
        }

        return specs;
    }
}

// ---------- Geracao das mutacoes a partir do corpo original ----------
var originalRoot = Json.parse(originalBody);
var allPaths = Paths.collect(originalRoot);

if (LOG_DISCOVERED_JSON_PATHS) {
    logging.logToOutput(
            "[PATHS] " + allPaths.size() + " caminhos JSON encontrados:"
    );

    for (var discoveredPath : allPaths) {
        logging.logToOutput(
                "[PATH] " + Paths.pathToString(discoveredPath)
        );
    }
}

var pathRules = new PathRules();

if (LOG_PATH_RULE_DIAGNOSTICS) {
    if (
            INCLUDE_PATH_PATTERNS != null
                    && !INCLUDE_PATH_PATTERNS.isEmpty()
    ) {
        for (String pattern : INCLUDE_PATH_PATTERNS) {
            if (pattern == null || pattern.isBlank()) {
                continue;
            }

            logging.logToOutput(
                    "[PATH RULE][INCLUDE] pattern="
                            + pattern
                            + " | matches="
                            + pathRules.countMatches(allPaths, pattern)
            );
        }
    }

    if (
            EXCLUDE_PATH_PATTERNS != null
                    && !EXCLUDE_PATH_PATTERNS.isEmpty()
    ) {
        for (String pattern : EXCLUDE_PATH_PATTERNS) {
            if (pattern == null || pattern.isBlank()) {
                continue;
            }

            logging.logToOutput(
                    "[PATH RULE][EXCLUDE] pattern="
                            + pattern
                            + " | matches="
                            + pathRules.countMatches(allPaths, pattern)
            );
        }
    }
}

var eligiblePaths = new ArrayList<List<Object>>();
var skippedNotIncludedCount = 0;
var skippedExcludedCount = 0;

// Primeira etapa: avalia TODOS os caminhos antes de aplicar MAX_MUTATIONS.
// Isso garante que exclusoes localizadas no fim do JSON sejam reconhecidas.
for (var path : allPaths) {
    var pathString = Paths.pathToString(path);

    if (!pathRules.isIncluded(pathString)) {
        skippedNotIncludedCount++;

        if (LOG_PATH_RULE_SKIPS) {
            logging.logToOutput(
                    "[SKIPPED BY INCLUSION] path=" + pathString
            );
        }

        continue;
    }

    if (pathRules.isExcluded(pathString)) {
        skippedExcludedCount++;

        if (LOG_PATH_RULE_SKIPS) {
            logging.logToOutput(
                    "[SKIPPED BY EXCLUSION] path=" + pathString
            );
        }

        continue;
    }

    eligiblePaths.add(path);
}

if (
        LOG_PATH_RULE_DIAGNOSTICS
                && EXCLUDE_PATH_PATTERNS != null
                && !EXCLUDE_PATH_PATTERNS.isEmpty()
                && skippedExcludedCount == 0
) {
    logging.logToOutput(
            "[PATH RULE][WARNING] Nenhum caminho foi excluido. "
                    + "Ative LOG_DISCOVERED_JSON_PATHS=true e copie "
                    + "o caminho exibido exatamente como aparece no log."
    );
}

var mutations = new ArrayList<Mutation>();
var skippedExceptionCount = 0;
var mutationLimitReached = false;

// Segunda etapa: gera mutacoes somente para os caminhos elegiveis.
outer:
for (var path : eligiblePaths) {
    var pathString = Paths.pathToString(path);

    var leafValue = Paths.getAt(
            Json.parse(originalBody),
            path
    );

    for (var spec : new Paths().specsFor(leafValue)) {
        if (pathRules.isException(pathString, spec)) {
            skippedExceptionCount++;

            if (LOG_PATH_RULE_SKIPS) {
                logging.logToOutput(
                        "[SKIPPED BY EXCEPTION] path="
                                + pathString
                                + " | test="
                                + spec.type()
                                + " | category="
                                + spec.category()
                );
            }

            continue;
        }

        if (mutations.size() >= MAX_MUTATIONS) {
            mutationLimitReached = true;
            break outer;
        }

        var clonedRoot = Json.parse(originalBody);
        var applied = Paths.applyAt(
                clonedRoot,
                path,
                spec
        );

        if (!applied) {
            continue;
        }

        mutations.add(
                new Mutation(
                        pathString,
                        spec.type(),
                        spec.description(),
                        spec.category(),
                        Json.write(clonedRoot)
                )
        );
    }
}

if (mutationLimitReached) {
    logging.logToOutput(
            "[LIMITE] MAX_MUTATIONS="
                    + MAX_MUTATIONS
                    + " reached after full evaluation of path rules."
    );
}

if (mutations.isEmpty()) {
    logging.logToOutput(
            "No mutation was generated. "
                    + "Check enabled categories and path rules."
    );

    logging.logToOutput(
            String.format(
                    "Path rules: %d outside inclusion | "
                            + "%d excluded | %d exception tests",
                    skippedNotIncludedCount,
                    skippedExcludedCount,
                    skippedExceptionCount
            )
    );

    return;
}

logging.logToOutput(
        "Generating "
                + mutations.size()
                + " mutations from "
                + eligiblePaths.size()
                + " eligible paths ("
                + allPaths.size()
                + " paths found)..."
);

logging.logToOutput(
        String.format(
                "Regras de caminho: %d fora da inclusao | "
                        + "%d excluidos | %d testes excepcionados",
                skippedNotIncludedCount,
                skippedExcludedCount,
                skippedExceptionCount
        )
);

// ---------- Checagem do servico HTTP ----------
var service = request.httpService();
if (service == null) {
    logging.logToError("Nao ha um HttpService valido nesta aba (host/porta ausentes). "
            + "Rode a action a partir de uma aba do Repeater com uma requisicao ja enviada, "
            + "nao apenas pelo botao 'Test' do editor sem uma requisicao real associada.");
    return;
}

// ---------- Baseline ----------
var baselineResult = api.http().sendRequest(request);

if (baselineResult == null || baselineResult.response() == null) {
    logging.logToError(
            "A requisicao baseline nao retornou resposta. "
                    + "Os testes foram abortados para evitar resultados inconsistentes."
    );
    return;
}

var baselineStatus = baselineResult.response().statusCode();
var baselineLength = baselineResult.response().body().length();
var baselineIsSuccess = baselineStatus >= BASELINE_STATUS_MIN
        && baselineStatus <= BASELINE_STATUS_MAX;

logging.logToOutput(
        String.format(
                "Baseline -> status=%d length=%d",
                baselineStatus,
                baselineLength
        )
);

if (REQUIRE_SUCCESSFUL_BASELINE && !baselineIsSuccess) {
    logging.logToError(
            "A baseline retornou HTTP "
                    + baselineStatus
                    + ". Como a requisicao original nao foi aceita com 2xx, "
                    + "os testes foram abortados para evitar findings inconsistentes."
    );
    return;
}

// ---------- Envio em lote das mutacoes ----------
// Move time-based payloads to the end so they don't delay other fast tests
mutations.sort((a, b) -> {
    boolean aIsTime = a.type().equals("STRING_SQLI_TIME");
    boolean bIsTime = b.type().equals("STRING_SQLI_TIME");
    if (aIsTime && !bIsTime) return 1;
    if (!aIsTime && bIsTime) return -1;
    return 0;
});

var mutatedRequests = new ArrayList<HttpRequest>();

for (var mutation : mutations) {
    mutatedRequests.add(
            request.withBody(mutation.body())
    );
}

var requestTimes = new long[mutatedRequests.size()];

List<HttpRequestResponse> responses = new ArrayList<>();
for (int i = 0; i < mutatedRequests.size(); i++) {
    long startTime = System.currentTimeMillis();
    try {
        HttpRequestResponse result = api.http().sendRequest(mutatedRequests.get(i));
        responses.add(result);
    } catch (Exception exception) {
        logging.logToError(
                "Failed to send mutated requests: "
                        + exception.getMessage()
        );
        responses.add(null);
    }
    requestTimes[i] = System.currentTimeMillis() - startTime;
}

if (responses == null) {
    logging.logToError(
            "Burp did not return the list of responses for mutations."
    );
    return;
}

// ---------- Analise ----------
// Regra:
// payload de teste + resposta HTTP 2xx = finding e envio ao Repeater.
//
// Respostas 4xx significam que o payload foi rejeitado.
// Respostas 5xx, redirecionamentos e ausencia de resposta ficam apenas no log.

var findingCount = 0;
var rejectedCount = 0;
var serverErrorCount = 0;
var redirectCount = 0;
var otherStatusCount = 0;
var noResponseCount = 0;
var repeaterSentCount = 0;
var organizerSentCount = 0;

var analyzedCount = Math.min(
        mutations.size(),
        responses.size()
);

for (int index = 0; index < analyzedCount; index++) {
    var mutation = mutations.get(index);

    // Nome diferente da variavel global "requestResponse" fornecida pelo Burp.
    var mutatedResult = responses.get(index);

    if (mutatedResult == null || mutatedResult.response() == null) {
        noResponseCount++;

        logging.logToOutput(
                String.format(
                        "[NO RESPONSE] path=%s | test=%s",
                        mutation.path(),
                        mutation.type()
                )
        );

        continue;
    }

    var mutatedResponse = mutatedResult.response();
    var status = mutatedResponse.statusCode();
    var length = mutatedResponse.body().length();
    var responseTime = requestTimes[index];
    var bodyStr = mutatedResponse.bodyToString();
    
    var timeDelayHit = mutation.type().equals("STRING_SQLI_TIME") && responseTime >= PAYLOAD_SQLI_TIME_DELAY_MS;
    var xssReflectionHit = CHECK_XSS_REFLECTION && mutation.type().equals("STRING_XSS") && bodyStr.contains(PAYLOAD_XSS);

    var accepted = (status >= FINDING_STATUS_MIN && status <= FINDING_STATUS_MAX) || timeDelayHit || xssReflectionHit;

    if (accepted && FILTER_EXACT_BASELINE_MATCH && length == baselineLength && !timeDelayHit && !xssReflectionHit) {
        accepted = false;
        logging.logToOutput(
                String.format(
                        "[IGNORED] path=%s | test=%s | Exact baseline match (%d bytes) filtered as false positive",
                        mutation.path(),
                        mutation.type(),
                        length
                )
        );
        continue;
    }

    if (accepted) {
        findingCount++;

        String findingMsg;
        if (timeDelayHit) {
            findingMsg = String.format(
                    "[FINDING] path=%s | test=%s | category=%s "
                            + "| HTTP=%d | time=%dms | time delay detected",
                    mutation.path(),
                    mutation.type(),
                    mutation.category(),
                    status,
                    responseTime
            );
        } else if (xssReflectionHit) {
            findingMsg = String.format(
                    "[FINDING] path=%s | test=%s | category=%s "
                            + "| HTTP=%d | size=%d | XSS payload reflected!",
                    mutation.path(),
                    mutation.type(),
                    mutation.category(),
                    status,
                    length
            );
        } else {
            findingMsg = String.format(
                    "[FINDING] path=%s | test=%s | category=%s "
                            + "| HTTP=%d | size=%d | payload accepted",
                    mutation.path(),
                    mutation.type(),
                    mutation.category(),
                    status,
                    length
            );
        }
        logging.logToOutput(findingMsg);

        if (repeaterSentCount < MAX_REPEATER_SENDS) {
            try {
                var repeaterTabName = RepeaterNames.create(
                        REPEATER_TAB_PREFIX,
                        mutation.type(),
                        mutation.path(),
                        findingCount,
                        REPEATER_TAB_NAME_MAX_LENGTH,
                        REPEATER_TAB_PATH_COMPONENTS,
                        REPEATER_TAB_INCLUDE_COUNTER
                );

                api.repeater().sendToRepeater(
                        mutatedResult.request(),
                        repeaterTabName
                );

                repeaterSentCount++;
            } catch (Exception exception) {
                logging.logToError(
                        "Could not send finding to Repeater: "
                                + mutation.type()
                                + " em "
                                + mutation.path()
                                + " | "
                                + exception.getMessage()
                );
            }
        } else if (SEND_EXCESS_TO_ORGANIZER) {
            try {
                api.organizer().sendToOrganizer(
                        mutatedResult.request()
                );

                organizerSentCount++;
            } catch (Exception exception) {
                logging.logToError(
                        "Nao foi possivel enviar o finding excedente ao Organizer: "
                                + exception.getMessage()
                );
            }
        }

        if (CREATE_AUDIT_ISSUES) {
            try {
                var issue = burp.api.montoya.scanner.audit.issues.AuditIssue.auditIssue(
                    "ParamValidator: " + mutation.type(),
                    "Found suspicious behavior at JSON path: " + mutation.path() + ".<br>" + findingMsg,
                    "Review the validation logic for this parameter.",
                    mutatedResult.request().url(),
                    burp.api.montoya.scanner.audit.issues.AuditIssueSeverity.HIGH,
                    burp.api.montoya.scanner.audit.issues.AuditIssueConfidence.FIRM,
                    null,
                    null,
                    burp.api.montoya.scanner.audit.issues.AuditIssueSeverity.HIGH,
                    mutatedResult
                );
                api.siteMap().add(issue);
            } catch (Exception e) {
                logging.logToError("Failed to create Audit Issue: " + e.getMessage());
            }
        }

        continue;
    }

    if (status >= 400 && status < 500) {
        rejectedCount++;

        if (LOG_REJECTED_PAYLOADS) {
            logging.logToOutput(
                    String.format(
                            "[REJECTED] path=%s | test=%s | HTTP=%d",
                            mutation.path(),
                            mutation.type(),
                            status
                    )
            );
        }

        continue;
    }

    if (status >= 500 && status < 600) {
        serverErrorCount++;

        if (LOG_SERVER_ERRORS) {
            logging.logToOutput(
                    String.format(
                            "[5XX ERROR] path=%s | test=%s | HTTP=%d "
                                    + "| not sent to Repeater",
                            mutation.path(),
                            mutation.type(),
                            status
                    )
            );
        }

        continue;
    }

    if (status >= 300 && status < 400) {
        redirectCount++;

        if (LOG_REDIRECTS) {
            logging.logToOutput(
                    String.format(
                            "[REDIRECT] path=%s | test=%s | HTTP=%d "
                                    + "| not sent to Repeater",
                            mutation.path(),
                            mutation.type(),
                            status
                    )
            );
        }

        continue;
    }

    otherStatusCount++;

    if (LOG_OTHER_STATUS) {
        logging.logToOutput(
                String.format(
                        "[OTHER STATUS] path=%s | test=%s | HTTP=%d",
                        mutation.path(),
                        mutation.type(),
                        status
                )
        );
    }
}

if (responses.size() < mutations.size()) {
    noResponseCount += mutations.size() - responses.size();
}

logging.logToOutput("------------------------------------------------------------");
logging.logToOutput(
        String.format(
                "Summary: %d findings | %d rejected | %d 5xx errors "
                        + "| %d redirects | %d other status "
                        + "| %d no response | %d mutations",
                findingCount,
                rejectedCount,
                serverErrorCount,
                redirectCount,
                otherStatusCount,
                noResponseCount,
                mutations.size()
        )
);

logging.logToOutput(
        String.format(
                "Destination: %d findings sent to Repeater "
                        + "| %d excess sent to Organizer "
                        + "| expected default group=%s",
                repeaterSentCount,
                organizerSentCount,
                REPEATER_GROUP_NAME
        )
);

if (findingCount == 0) {
    logging.logToOutput(
            "No test payload received an HTTP 2xx response."
    );
}

if (findingCount > MAX_REPEATER_SENDS) {
    if (SEND_EXCESS_TO_ORGANIZER) {
        logging.logToOutput(
                "O limite de "
                        + MAX_REPEATER_SENDS
                        + " abas do Repeater foi atingido. "
                        + "Os findings excedentes foram enviados ao Organizer."
        );
    } else {
        logging.logToOutput(
                "O limite de "
                        + MAX_REPEATER_SENDS
                        + " abas do Repeater foi atingido. "
                        + "Os findings excedentes ficaram somente no log."
        );
    }
}
