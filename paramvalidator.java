// ============================================================
// JSON Parameter Validation Tester - Burp Custom Action (v2)
// ------------------------------------------------------------
// Cole este script em: Repeater > Custom actions > New > Blank
// Rode com o botao direito sobre uma requisicao com corpo JSON.
//
// Mudancas da v2:
//  - Mutacoes sao categorizadas (STRUCTURAL / TYPE_CONFUSION /
//    BOUNDARY / INJECTION) e cada categoria pode ser ligada ou
//    desligada abaixo, para controlar volume/tempo de execucao.
//  - Severidade nao depende so de "status diferente do baseline".
//    A regra de finding e objetiva: qualquer mutacao que receba
//    resposta HTTP 2xx foi aceita e e enviada ao Repeater.
//  - Respostas 4xx, 5xx, redirecionamentos e falhas de conexao
//    nao sao enviadas ao Repeater; ficam apenas registradas no log.
//  - Findings acima do limite de abas sao enviados ao Organizer.
//
// Sem dependencias externas: parser/serializer JSON proprio,
// ja que Custom Actions nao permitem adicionar bibliotecas.
// ============================================================

// ---------------- CONFIGURACAO ----------------
var TEST_STRUCTURAL = true;     // null, campo removido, {}/[] no lugar do valor
var TEST_TYPE_CONFUSION = true; // string<->numero<->boolean
var TEST_BOUNDARY = true;       // vazio, zero, negativo, overflow, string gigante
var TEST_INJECTION = true;      // SQLi, XSS, NoSQLi, path traversal, format string, unicode

var MAX_MUTATIONS = 60;         // teto global de mutacoes geradas
var MAX_REPEATER_SENDS = 10;    // teto de abas novas no Repeater
// ------------------------------------------------

var request = requestResponse.request();
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

    static List<MutationSpec> specsFor(Object value, boolean testStructural, boolean testTypeConfusion,
                                        boolean testBoundary, boolean testInjection) {
        var specs = new ArrayList<MutationSpec>();

        if (testStructural) {
            specs.add(new MutationSpec("NULL_VALUE", "Valor substituido por null", null, false, Category.STRUCTURAL));
            specs.add(new MutationSpec("FIELD_REMOVED", "Campo removido do corpo", null, true, Category.STRUCTURAL));
            specs.add(new MutationSpec("TYPE_EMPTY_OBJECT", "Valor substituido por objeto vazio {}", new LinkedHashMap<String, Object>(), false, Category.STRUCTURAL));
            specs.add(new MutationSpec("TYPE_EMPTY_ARRAY", "Valor substituido por array vazio []", new ArrayList<Object>(), false, Category.STRUCTURAL));
        }

        if (value instanceof String) {
            if (testBoundary) {
                specs.add(new MutationSpec("EMPTY_STRING", "String vazia", "", false, Category.BOUNDARY));
                specs.add(new MutationSpec("STRING_LONG", "String muito longa (10000 chars)", "A".repeat(10000), false, Category.BOUNDARY));
            }
            if (testInjection) {
                specs.add(new MutationSpec("STRING_SQLI", "Payload de SQL Injection", "' OR '1'='1", false, Category.INJECTION));
                specs.add(new MutationSpec("STRING_XSS", "Payload de XSS", "<script>alert(1)</script>", false, Category.INJECTION));
                specs.add(new MutationSpec("STRING_PATH_TRAVERSAL", "Payload de Path Traversal", "../../../../etc/passwd", false, Category.INJECTION));
                specs.add(new MutationSpec("STRING_NOSQLI", "Payload de NoSQL Injection (string)", "{\"$ne\": null}", false, Category.INJECTION));
                specs.add(new MutationSpec("STRING_FORMAT", "Format string payload", "%s%x%n", false, Category.INJECTION));
                specs.add(new MutationSpec("STRING_UNICODE", "Payload unicode / RTL override", "\u202Etest\uD83D\uDE00", false, Category.INJECTION));
            }
            if (testTypeConfusion) {
                specs.add(new MutationSpec("TYPE_NUMBER", "String substituida por numero (0)", 0L, false, Category.TYPE_CONFUSION));
                specs.add(new MutationSpec("TYPE_BOOLEAN", "String substituida por boolean (true)", Boolean.TRUE, false, Category.TYPE_CONFUSION));
            }
        } else if (value instanceof Long || value instanceof Integer) {
            if (testBoundary) {
                specs.add(new MutationSpec("NUMBER_ZERO", "Valor zero", 0L, false, Category.BOUNDARY));
                specs.add(new MutationSpec("NUMBER_NEGATIVE", "Valor negativo", -1L, false, Category.BOUNDARY));
                specs.add(new MutationSpec("NUMBER_OVERFLOW", "Overflow (Long.MAX_VALUE)", Long.MAX_VALUE, false, Category.BOUNDARY));
                specs.add(new MutationSpec("NUMBER_FLOAT", "Float onde inteiro era esperado", 1.5, false, Category.BOUNDARY));
            }
            if (testTypeConfusion) {
                specs.add(new MutationSpec("TYPE_STRING", "Numero substituido por string nao numerica", "abc", false, Category.TYPE_CONFUSION));
                specs.add(new MutationSpec("TYPE_STRING_NUMERIC", "Numero substituido por string numerica", "123", false, Category.TYPE_CONFUSION));
            }
        } else if (value instanceof Double) {
            if (testBoundary) {
                specs.add(new MutationSpec("NUMBER_ZERO", "Valor zero", 0.0, false, Category.BOUNDARY));
                specs.add(new MutationSpec("NUMBER_NEGATIVE", "Valor negativo", -1.5, false, Category.BOUNDARY));
            }
            if (testTypeConfusion) {
                specs.add(new MutationSpec("TYPE_STRING", "Numero substituido por string", "abc", false, Category.TYPE_CONFUSION));
            }
        } else if (value instanceof Boolean b) {
            if (testBoundary) {
                specs.add(new MutationSpec("BOOLEAN_FLIP", "Boolean invertido", !b, false, Category.BOUNDARY));
            }
            if (testTypeConfusion) {
                specs.add(new MutationSpec("TYPE_STRING", "Boolean substituido por string \"true\"", "true", false, Category.TYPE_CONFUSION));
                specs.add(new MutationSpec("TYPE_NUMBER", "Boolean substituido por numero (1)", 1L, false, Category.TYPE_CONFUSION));
            }
        }

        return specs;
    }
}

// ---------- Geracao das mutacoes a partir do corpo original ----------
var originalRoot = Json.parse(originalBody);
var allPaths = Paths.collect(originalRoot);

var mutations = new ArrayList<Mutation>();
outer:
for (var path : allPaths) {
    var leafValue = Paths.getAt(Json.parse(originalBody), path);
    for (var spec : Paths.specsFor(leafValue, TEST_STRUCTURAL, TEST_TYPE_CONFUSION, TEST_BOUNDARY, TEST_INJECTION)) {
        if (mutations.size() >= MAX_MUTATIONS) break outer;
        var clonedRoot = Json.parse(originalBody);
        var applied = Paths.applyAt(clonedRoot, path, spec);
        if (!applied) continue;
        mutations.add(new Mutation(Paths.pathToString(path), spec.type(), spec.description(), spec.category(), Json.write(clonedRoot)));
    }
}

if (mutations.isEmpty()) {
    logging.logToOutput("Nenhum parametro mutavel encontrado no corpo JSON (ou todas as categorias de teste estao desligadas).");
    return;
}

logging.logToOutput("Gerando " + mutations.size() + " mutacoes a partir de " + allPaths.size() + " campos JSON...");

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
var baselineIsSuccess = baselineStatus >= 200 && baselineStatus < 300;

logging.logToOutput(
        String.format(
                "Baseline -> status=%d length=%d",
                baselineStatus,
                baselineLength
        )
);

if (!baselineIsSuccess) {
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
    var accepted = status >= 200 && status < 300;

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
                api.repeater().sendToRepeater(
                        mutatedResult.request(),
                        "JSON-INPUT-VALIDATION-"
                                + mutation.type()
                                + "-"
                                + index
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
        } else {
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

        logging.logToOutput(
                String.format(
                        "[REJEITADO] path=%s | teste=%s | HTTP=%d",
                        mutation.path(),
                        mutation.type(),
                        status
                )
        );

        continue;
    }

    if (status >= 500 && status < 600) {
        serverErrorCount++;

        logging.logToOutput(
                String.format(
                        "[ERRO 5XX] path=%s | teste=%s | HTTP=%d "
                                + "| nao enviado ao Repeater",
                        mutation.path(),
                        mutation.type(),
                        status
                )
        );

        continue;
    }

    if (status >= 300 && status < 400) {
        redirectCount++;

        logging.logToOutput(
                String.format(
                        "[REDIRECIONAMENTO] path=%s | teste=%s | HTTP=%d "
                                + "| nao enviado ao Repeater",
                        mutation.path(),
                        mutation.type(),
                        status
                )
        );

        continue;
    }

    otherStatusCount++;

    logging.logToOutput(
            String.format(
                    "[OUTRO STATUS] path=%s | teste=%s | HTTP=%d",
                    mutation.path(),
                    mutation.type(),
                    status
            )
    );
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
                        + "| %d excedentes enviados ao Organizer",
                repeaterSentCount,
                organizerSentCount
        )
);

if (findingCount == 0) {
    logging.logToOutput(
            "Nenhum payload de teste recebeu resposta HTTP 2xx."
    );
}

if (findingCount > MAX_REPEATER_SENDS) {
    logging.logToOutput(
            "O limite de "
                    + MAX_REPEATER_SENDS
                    + " abas do Repeater foi atingido. "
                    + "Os findings excedentes foram enviados ao Organizer."
    );
}
