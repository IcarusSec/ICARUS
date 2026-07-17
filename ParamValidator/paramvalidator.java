// ============================================================
// JSON Parameter Validation Tester
// Burp Suite Custom Action
//
// Authors: Victor Lima | Adan Ferreira
//
// Description:
// JSON Parameter validation for API security testing.
//
// Version: 0.1.0
// Last update: 2026-07-16
// ============================================================

// ============================================================
// ================= USO BÁSICO =================
// ------------------------------------------------------------
// Cole este script em: Repeater > Custom actions > New > Blank
// Rode com o botao direito sobre uma requisicao com corpo JSON.
//
// Recursos principais:
//  - Mutacoes sao categorizadas (STRUCTURAL / TYPE_CONFUSION /
//    BOUNDARY / INJECTION) e cada categoria pode ser ligada ou
//    desligada abaixo, para controlar volume/tempo de execucao
//
//  - Respostas 4xx, 5xx, redirecionamentos e falhas de conexao
//    nao sao enviadas ao Repeater; ficam apenas registradas no log.
//  - Findings acima do limite de abas sao enviados ao Organizer.
//  - Regras por caminho permitem inclusao, exclusao e excecoes
//    especificas por teste ou categoria, com suporte a curingas.
//
// Sem dependencias externas: parser/serializer JSON proprio,
// ja que Custom Actions nao permitem adicionar bibliotecas.
// ============================================================

// ================= CONFIGURACAO DO USUÁRIO =================
//
// Altere somente esta secao para personalizar a execucao.

// ---------- Categorias principais ----------
var TEST_STRUCTURAL = true;       // null, remocao, objeto vazio e array vazio
var TEST_TYPE_CONFUSION = true;   // troca entre string, numero e boolean
var TEST_BOUNDARY = true;         // vazio, negativos, overflow e strings longas
var TEST_INJECTION = true;        // XSS, SQLi, NoSQLi, traversal etc.

// ---------- Testes estruturais individuais ----------
var TEST_NULL_VALUE = true;
var TEST_FIELD_REMOVAL = true;
var TEST_EMPTY_OBJECT = true;
var TEST_EMPTY_ARRAY = true;

// ---------- Testes de limite individuais ----------
var TEST_EMPTY_STRING = true;
var TEST_LONG_STRING = true;
var TEST_NUMBER_ZERO = true;
var TEST_NUMBER_NEGATIVE = true;
var TEST_NUMBER_OVERFLOW = true;
var TEST_INTEGER_AS_FLOAT = true;
var TEST_BOOLEAN_FLIP = true;

// ---------- Testes de injecao individuais ----------
var TEST_SQLI = true;
var TEST_XSS = true;
var TEST_PATH_TRAVERSAL = true;
var TEST_NOSQLI = true;
var TEST_FORMAT_STRING = true;
var TEST_UNICODE = true;

// ---------- Testes de confusao de tipo ----------
var TEST_STRING_AS_NUMBER = true;
var TEST_STRING_AS_BOOLEAN = true;
var TEST_NUMBER_AS_STRING = true;
var TEST_NUMBER_AS_NUMERIC_STRING = true;
var TEST_BOOLEAN_AS_STRING = true;
var TEST_BOOLEAN_AS_NUMBER = true;

// ---------- Regras por caminho JSON ----------
//
// Sintaxe de caminhos:
//   $.user.email          caminho exato
//   $.users[*].email      qualquer indice de array
//   $.metadata.*          qualquer filho direto
//   $.metadata.**         qualquer descendente
//
// INCLUSAO:
//   - Lista vazia: todos os caminhos podem ser testados.
//   - Lista preenchida: somente caminhos que casarem com algum padrao.
//
// EXCLUSAO:
//   - Caminhos que casarem aqui nao recebem nenhuma mutacao.
//   - A exclusao tem prioridade sobre a inclusao.
//
// EXCECOES:
//   - Impedem apenas um teste ou uma categoria em determinado caminho.
//   - Formato: "PADRAO_DO_CAMINHO::REGRA"
//   - Regras aceitas:
//       ALL
//       CATEGORY:STRUCTURAL
//       CATEGORY:TYPE_CONFUSION
//       CATEGORY:BOUNDARY
//       CATEGORY:INJECTION
//       ou o nome exato do teste, como STRING_XSS ou NULL_VALUE.

List<String> INCLUDE_PATH_PATTERNS = List.of(
        // "$.user.**",
        // "$.items[*].**"
);

// Exemplos:
// "$.chosen_discount"    -> exclui somente esse caminho
// "$.chosen_discount.**" -> exclui o caminho e todos os descendentes
// "$.items[*].internalId" -> exclui o campo em qualquer item do array
List<String> EXCLUDE_PATH_PATTERNS = List.of(
         "$.chosen_products.quantity"
        // "$.audit.**"
);

List<String> PATH_TEST_EXCEPTIONS = List.of(
        // "$.profile.nickname::NULL_VALUE",
        // "$.description::CATEGORY:STRUCTURAL",
        // "$.items[*].internalId::ALL"
);

// Mostra no log os caminhos ignorados pelas regras.
var LOG_PATH_RULE_SKIPS = true;

// Mostra todos os caminhos JSON encontrados antes das mutacoes.
// Util para confirmar o nome exato de um campo durante a configuracao.
var LOG_DISCOVERED_JSON_PATHS = false;

// ---------- Payloads editaveis ----------
var PAYLOAD_SQLI = "' OR '1'='1";
var PAYLOAD_XSS = "<script>alert(1)</script>";
var PAYLOAD_PATH_TRAVERSAL = "../../../../etc/passwd";
var PAYLOAD_NOSQLI = "{\"$ne\": null}";
var PAYLOAD_FORMAT_STRING = "%s%x%n";
var PAYLOAD_UNICODE = "\u202Etest\uD83D\uDE00";

var PAYLOAD_NON_NUMERIC_STRING = "abc";
var PAYLOAD_NUMERIC_STRING = "123";
var PAYLOAD_BOOLEAN_STRING = "true";
var PAYLOAD_LONG_STRING_CHARACTER = "A";

// ---------- Valores de limite ----------
var LONG_STRING_LENGTH = 10000;
var NEGATIVE_INTEGER_VALUE = -1L;
var NEGATIVE_DECIMAL_VALUE = -1.5;
var INTEGER_AS_FLOAT_VALUE = 1.5;
var STRING_AS_NUMBER_VALUE = 0L;
var BOOLEAN_AS_NUMBER_VALUE = 1L;

// ---------- Criterio de finding ----------
// Qualquer resposta dentro deste intervalo e considerada aceitacao.
var FINDING_STATUS_MIN = 200;
var FINDING_STATUS_MAX = 299;

// Exige que a requisicao original tambem retorne um status neste intervalo.
var REQUIRE_SUCCESSFUL_BASELINE = true;
var BASELINE_STATUS_MIN = 200;
var BASELINE_STATUS_MAX = 299;

// ---------- Volume e destinos ----------
var MAX_MUTATIONS = 60;
var MAX_REPEATER_SENDS = 10;

// Quando o limite do Repeater for atingido, envia o excedente ao Organizer.
var SEND_EXCESS_TO_ORGANIZER = true;

// ---------- Organizacao no Repeater ----------
//
// A Montoya API usada pelas Custom Actions nao permite criar ou selecionar
// grupos de abas por codigo. Para que os findings entrem em um grupo:
// 1. crie o grupo manualmente no Repeater;
// 2. selecione esse grupo em Settings > Tools > Repeater > Default tab group.
//
// Esta variavel identifica no log qual grupo deve ser configurado.
var REPEATER_GROUP_NAME = "JSON Validation";

// Prefixo curto usado nos nomes das abas.
var REPEATER_TAB_PREFIX = "IV";

// Quantidade maxima de caracteres no nome completo da aba.
var REPEATER_TAB_NAME_MAX_LENGTH = 28;

// Quantos componentes finais do caminho JSON aparecem no nome.
var REPEATER_TAB_PATH_COMPONENTS = 2;

// Adiciona um contador curto ao final para evitar nomes repetidos.
var REPEATER_TAB_INCLUDE_COUNTER = true;

// Exibe no inicio uma orientacao sobre o grupo padrao.
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
            "[REPEATER] Para agrupar automaticamente as abas desta action, "
                    + "crie o grupo '"
                    + REPEATER_GROUP_NAME
                    + "' e selecione-o em Settings > Tools > Repeater "
                    + "> Default tab group."
    );
}

var contentType = request.headerValue("Content-Type");
var originalBody = request.bodyToString();

var looksLikeJson = (contentType != null && contentType.toLowerCase().contains("json"))
        || (originalBody != null && (originalBody.trim().startsWith("{") || originalBody.trim().startsWith("[")));

if (originalBody == null || originalBody.isBlank() || !looksLikeJson) {
    logging.logToOutput("Corpo da requisicao vazio ou nao parece ser JSON - nada para testar.");
    return;
}

// ---------- Parser/serializer JSON minimo ----------
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
                regex.append("\\\\");
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
                        "Valor substituido por null",
                        null,
                        false,
                        Category.STRUCTURAL
                ));
            }

            if (TEST_FIELD_REMOVAL) {
                specs.add(new MutationSpec(
                        "FIELD_REMOVED",
                        "Campo removido do corpo",
                        null,
                        true,
                        Category.STRUCTURAL
                ));
            }

            if (TEST_EMPTY_OBJECT) {
                specs.add(new MutationSpec(
                        "TYPE_EMPTY_OBJECT",
                        "Valor substituido por objeto vazio {}",
                        new LinkedHashMap<String, Object>(),
                        false,
                        Category.STRUCTURAL
                ));
            }

            if (TEST_EMPTY_ARRAY) {
                specs.add(new MutationSpec(
                        "TYPE_EMPTY_ARRAY",
                        "Valor substituido por array vazio []",
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
                            "String vazia",
                            "",
                            false,
                            Category.BOUNDARY
                    ));
                }

                if (TEST_LONG_STRING) {
                    specs.add(new MutationSpec(
                            "STRING_LONG",
                            "String muito longa (" + LONG_STRING_LENGTH + " chars)",
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
                            "Payload de SQL Injection",
                            PAYLOAD_SQLI,
                            false,
                            Category.INJECTION
                    ));
                }

                if (TEST_XSS) {
                    specs.add(new MutationSpec(
                            "STRING_XSS",
                            "Payload de XSS",
                            PAYLOAD_XSS,
                            false,
                            Category.INJECTION
                    ));
                }

                if (TEST_PATH_TRAVERSAL) {
                    specs.add(new MutationSpec(
                            "STRING_PATH_TRAVERSAL",
                            "Payload de Path Traversal",
                            PAYLOAD_PATH_TRAVERSAL,
                            false,
                            Category.INJECTION
                    ));
                }

                if (TEST_NOSQLI) {
                    specs.add(new MutationSpec(
                            "STRING_NOSQLI",
                            "Payload de NoSQL Injection (string)",
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
                            "String substituida por numero",
                            STRING_AS_NUMBER_VALUE,
                            false,
                            Category.TYPE_CONFUSION
                    ));
                }

                if (TEST_STRING_AS_BOOLEAN) {
                    specs.add(new MutationSpec(
                            "TYPE_BOOLEAN",
                            "String substituida por boolean",
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
                            "Valor zero",
                            0L,
                            false,
                            Category.BOUNDARY
                    ));
                }

                if (TEST_NUMBER_NEGATIVE) {
                    specs.add(new MutationSpec(
                            "NUMBER_NEGATIVE",
                            "Valor negativo",
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
                            "Float onde inteiro era esperado",
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
                            "Numero substituido por string nao numerica",
                            PAYLOAD_NON_NUMERIC_STRING,
                            false,
                            Category.TYPE_CONFUSION
                    ));
                }

                if (TEST_NUMBER_AS_NUMERIC_STRING) {
                    specs.add(new MutationSpec(
                            "TYPE_STRING_NUMERIC",
                            "Numero substituido por string numerica",
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
                            "Valor zero",
                            0.0,
                            false,
                            Category.BOUNDARY
                    ));
                }

                if (TEST_NUMBER_NEGATIVE) {
                    specs.add(new MutationSpec(
                            "NUMBER_NEGATIVE",
                            "Valor negativo",
                            NEGATIVE_DECIMAL_VALUE,
                            false,
                            Category.BOUNDARY
                    ));
                }
            }

            if (TEST_TYPE_CONFUSION && TEST_NUMBER_AS_STRING) {
                specs.add(new MutationSpec(
                        "TYPE_STRING",
                        "Numero substituido por string",
                        PAYLOAD_NON_NUMERIC_STRING,
                        false,
                        Category.TYPE_CONFUSION
                ));
            }
        } else if (value instanceof Boolean b) {
            if (TEST_BOUNDARY && TEST_BOOLEAN_FLIP) {
                specs.add(new MutationSpec(
                        "BOOLEAN_FLIP",
                        "Boolean invertido",
                        !b,
                        false,
                        Category.BOUNDARY
                ));
            }

            if (TEST_TYPE_CONFUSION) {
                if (TEST_BOOLEAN_AS_STRING) {
                    specs.add(new MutationSpec(
                            "TYPE_STRING",
                            "Boolean substituido por string",
                            PAYLOAD_BOOLEAN_STRING,
                            false,
                            Category.TYPE_CONFUSION
                    ));
                }

                if (TEST_BOOLEAN_AS_NUMBER) {
                    specs.add(new MutationSpec(
                            "TYPE_NUMBER",
                            "Boolean substituido por numero",
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
                    "[IGNORADO POR INCLUSAO] path=" + pathString
            );
        }

        continue;
    }

    if (pathRules.isExcluded(pathString)) {
        skippedExcludedCount++;

        if (LOG_PATH_RULE_SKIPS) {
            logging.logToOutput(
                    "[IGNORADO POR EXCLUSAO] path=" + pathString
            );
        }

        continue;
    }

    eligiblePaths.add(path);
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
                        "[IGNORADO POR EXCECAO] path="
                                + pathString
                                + " | teste="
                                + spec.type()
                                + " | categoria="
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
                    + " atingido apos a avaliacao completa das regras de caminho."
    );
}

if (mutations.isEmpty()) {
    logging.logToOutput(
            "Nenhuma mutacao foi gerada. "
                    + "Verifique as categorias habilitadas e as regras de caminho."
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

    return;
}

logging.logToOutput(
        "Gerando "
                + mutations.size()
                + " mutacoes a partir de "
                + eligiblePaths.size()
                + " caminhos elegiveis ("
                + allPaths.size()
                + " caminhos encontrados)..."
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
var mutatedRequests = new ArrayList<HttpRequest>();

for (var mutation : mutations) {
    mutatedRequests.add(
            request.withBody(mutation.body())
    );
}

List<HttpRequestResponse> responses;

try {
    responses = api.http().sendRequests(mutatedRequests);
} catch (Exception exception) {
    logging.logToError(
            "Falha ao enviar as requisicoes mutadas: "
                    + exception.getMessage()
    );
    return;
}

if (responses == null) {
    logging.logToError(
            "O Burp nao retornou a lista de respostas das mutacoes."
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
                        "[SEM RESPOSTA] path=%s | teste=%s",
                        mutation.path(),
                        mutation.type()
                )
        );

        continue;
    }

    var mutatedResponse = mutatedResult.response();
    var status = mutatedResponse.statusCode();
    var length = mutatedResponse.body().length();
    var accepted = status >= FINDING_STATUS_MIN
            && status <= FINDING_STATUS_MAX;

    if (accepted) {
        findingCount++;

        logging.logToOutput(
                String.format(
                        "[FINDING] path=%s | teste=%s | categoria=%s "
                                + "| HTTP=%d | tamanho=%d | payload aceito",
                        mutation.path(),
                        mutation.type(),
                        mutation.category(),
                        status,
                        length
                )
        );

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
                        "Nao foi possivel enviar o finding ao Repeater: "
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

        continue;
    }

    if (status >= 400 && status < 500) {
        rejectedCount++;

        if (LOG_REJECTED_PAYLOADS) {
            logging.logToOutput(
                    String.format(
                            "[REJEITADO] path=%s | teste=%s | HTTP=%d",
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
                            "[ERRO 5XX] path=%s | teste=%s | HTTP=%d "
                                    + "| nao enviado ao Repeater",
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
                            "[REDIRECIONAMENTO] path=%s | teste=%s | HTTP=%d "
                                    + "| nao enviado ao Repeater",
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
                        "[OUTRO STATUS] path=%s | teste=%s | HTTP=%d",
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
                "Resumo: %d findings | %d rejeitados | %d erros 5xx "
                        + "| %d redirecionamentos | %d outros status "
                        + "| %d sem resposta | %d mutacoes",
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
                "Destino: %d findings enviados ao Repeater "
                        + "| %d excedentes enviados ao Organizer "
                        + "| grupo padrao esperado=%s",
                repeaterSentCount,
                organizerSentCount,
                REPEATER_GROUP_NAME
        )
);

if (findingCount == 0) {
    logging.logToOutput(
            "Nenhum payload de teste recebeu resposta HTTP 2xx."
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
