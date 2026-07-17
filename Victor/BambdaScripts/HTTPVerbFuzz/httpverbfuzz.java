// ============================================================
// HTTP Verb Tester
// Burp Suite Custom Action
//
// Authors: Victor Lima | Adan Ferreira
//
// Description:
// Basic HTTP verb validation for API security testing.
//
// Version: 0.1.0
// Last update: 2026-07-16
// ============================================================

// ============================================================
// ================= USO BÁSICO =================
// ------------------------------------------------------------
// Cole este script em:
// Repeater > Custom actions > New > Blank
//
// Execute somente em sistemas que voce possui autorizacao para testar.
//
// Objetivo:
//  - Criar variacoes da requisicao atual usando outros metodos HTTP.
//  - Identificar metodos alternativos que retornam status configurados
//    como "aceitos".
//  - Enviar resultados relevantes ao Repeater.
//
// Observacao:
//  - Um metodo alternativo retornar 2xx nao confirma vulnerabilidade.
//    O resultado significa "metodo aceito e deve ser revisado".
//  - Metodos que alteram estado ficam desabilitados por padrao.
// ============================================================


// ================= CONFIGURACAO DO USUÁRIO =================
//
// Altere somente esta secao para personalizar a execucao.


// ---------- Metodos testados ----------

var TEST_GET = true;
var TEST_HEAD = true;
var TEST_POST = false;
var TEST_PUT = false;
var TEST_PATCH = false;
var TEST_DELETE = false;
var TEST_OPTIONS = true;
var TEST_TRACE = true;

// CONNECT fica desabilitado por padrao.
// Pode ter comportamento especial em proxies e servidores.
var TEST_CONNECT = false;


// ---------- Metodo original ----------
//
// TEST_POST=true apenas habilita POST na lista.
// Se a requisicao original ja for POST e SKIP_ORIGINAL_METHOD=true,
// ele ainda sera removido do plano.
//
// Para testar novamente o metodo original:
//   TEST_POST = true;
//   SKIP_ORIGINAL_METHOD = false;

var SKIP_ORIGINAL_METHOD = true;


// ---------- Modo de envio ----------
//
// "SEQUENTIAL" e o modo recomendado para Custom Actions.
// Cada requisicao e enviada individualmente e sua resposta e analisada.
//
// "BATCH" usa sendRequests(). Pode ser mais rapido, mas algumas versoes
// ou configuracoes do Burp podem retornar itens sem resposta.

var REQUEST_MODE = "SEQUENTIAL";

// Intervalo opcional entre requisicoes no modo sequencial.
var DELAY_BETWEEN_REQUESTS_MS = 0;


// ---------- Protecao contra alteracao de estado ----------
//
// Quando false, POST, PUT, PATCH e DELETE nao serao enviados,
// mesmo que os testes individuais acima estejam habilitados.

var ENABLE_STATE_CHANGING_METHODS = true;


// ---------- Estrategia de corpo ----------
//
// Valores permitidos:
//
// "AUTO"
//   GET, HEAD, OPTIONS, TRACE e CONNECT: remove o corpo.
//   POST, PUT, PATCH e DELETE: mantem o corpo original.
//
// "KEEP"
//   Mantem o corpo original em todos os metodos.
//
// "REMOVE"
//   Remove o corpo em todos os metodos.

var BODY_STRATEGY = "AUTO";

// Remove Content-Type quando o corpo for removido.
var REMOVE_CONTENT_TYPE_WITH_BODY = true;

// Remove Transfer-Encoding quando o corpo for removido.
var REMOVE_TRANSFER_ENCODING_WITH_BODY = true;


// ---------- Criterio de metodo aceito ----------
//
// Qualquer resposta neste intervalo sera tratada como metodo aceito.

var ACCEPTED_STATUS_MIN = 200;
var ACCEPTED_STATUS_MAX = 299;


// ---------- Tratamento de autenticacao/autorizacao ----------
//
// 401 e 403 podem indicar que o metodo existe, mas exige credenciais
// ou privilegios diferentes. Por padrao ficam apenas no log.

var REPORT_401_AS_POSSIBLE_SUPPORTED = true;
var REPORT_403_AS_POSSIBLE_SUPPORTED = true;

var SEND_AUTH_RESPONSES_TO_REPEATER = false;


// ---------- Redirecionamentos ----------
//
// O envio HTTP da Montoya API segue a configuracao do Burp.
// Esta opcao controla apenas a classificacao do resultado recebido.

var REPORT_REDIRECTS = true;
var SEND_REDIRECTS_TO_REPEATER = false;


// ---------- Erros de servidor ----------

var REPORT_SERVER_ERRORS = true;
var SEND_SERVER_ERRORS_TO_REPEATER = false;


// ---------- OPTIONS / Allow ----------

var CHECK_ALLOW_HEADER = true;

// Quando true, OPTIONS e adicionado ao plano mesmo se TEST_OPTIONS=false.
var FORCE_OPTIONS_FOR_ALLOW_CHECK = true;

// Reporta metodos que retornaram 2xx, mas nao aparecem no Allow.
var REPORT_ACCEPTED_NOT_IN_ALLOW = true;

// Reporta metodos declarados no Allow, mas que nao foram testados.
var LOG_UNTESTED_ALLOW_METHODS = true;

// Envia ao Repeater um metodo aceito que nao foi declarado no Allow.
var SEND_ALLOW_MISMATCH_TO_REPEATER = true;


// ---------- TRACE ----------
//
// Quando TRACE retornar 2xx, verifica se partes da requisicao
// aparecem refletidas na resposta.

var CHECK_TRACE_REFLECTION = true;

// Texto procurado na resposta TRACE.
// O script adiciona este header somente na requisicao TRACE.
var TRACE_MARKER_HEADER_NAME = "X-HTTP-Verb-Test";
var TRACE_MARKER_VALUE = "burp-http-verb-check";


// ---------- Baseline opcional ----------
//
// Reenviar a requisicao original pode repetir uma operacao.
// Por isso esta desabilitado por padrao.

var SEND_BASELINE_REQUEST = false;


// ---------- Destinos ----------

var SEND_ACCEPTED_TO_REPEATER = true;
var SEND_EXCESS_TO_ORGANIZER = true;

// Envia ao Organizer toda requisicao executada que nao tenha gerado
// uma aba no Repeater.
//
// Inclui, conforme o resultado:
// - metodos rejeitados
// - respostas 401/403
// - redirecionamentos
// - erros 5xx
// - outros status
//
// Requisicoes sem resposta nao podem ser enviadas ao Organizer,
// pois nao existe HttpRequestResponse completo para armazenar.
var SEND_NON_REPEATER_RESULTS_TO_ORGANIZER = true;

var MAX_REPEATER_TABS = 12;

// Prefixo curto para nomes de abas.
var REPEATER_TAB_PREFIX = "HV";

// Tamanho maximo do nome da aba.
var REPEATER_TAB_NAME_MAX_LENGTH = 22;


// ---------- Logs ----------

var LOG_REJECTED_METHODS = true;
var LOG_REDIRECTS = true;
var LOG_AUTH_RESPONSES = true;
var LOG_SERVER_ERRORS = true;
var LOG_OTHER_RESPONSES = true;
var LOG_REQUEST_PLAN = true;


// ============================================================
// FIM DA CONFIGURACAO
// ============================================================


// ---------- Tipos auxiliares ----------

record VerbResult(
        String method,
        HttpRequest request,
        HttpRequestResponse requestResponse,
        int status,
        int responseLength,
        String allowHeader,
        boolean accepted,
        boolean traceReflected
) {}

class VerbTools {

    static boolean isStateChanging(String method) {
        return "POST".equals(method)
                || "PUT".equals(method)
                || "PATCH".equals(method)
                || "DELETE".equals(method);
    }

    static boolean normallyHasBody(String method) {
        return "POST".equals(method)
                || "PUT".equals(method)
                || "PATCH".equals(method)
                || "DELETE".equals(method);
    }

    static HttpRequest applyBodyStrategy(
            HttpRequest original,
            HttpRequest mutated,
            String method,
            String strategy,
            boolean removeContentType,
            boolean removeTransferEncoding
    ) {
        var normalizedStrategy = strategy == null
                ? "AUTO"
                : strategy.trim().toUpperCase();

        var removeBody = false;

        if ("REMOVE".equals(normalizedStrategy)) {
            removeBody = true;
        } else if ("KEEP".equals(normalizedStrategy)) {
            removeBody = false;
        } else {
            removeBody = !normallyHasBody(method);
        }

        if (!removeBody) {
            return mutated.withBody(original.body());
        }

        var result = mutated.withBody("");

        if (removeContentType) {
            result = result.withRemovedHeader("Content-Type");
        }

        if (removeTransferEncoding) {
            result = result.withRemovedHeader("Transfer-Encoding");
        }

        return result;
    }

    static boolean isAccepted(
            int status,
            int minimum,
            int maximum
    ) {
        return status >= minimum && status <= maximum;
    }

    static List<String> parseAllow(String allowHeader) {
        var result = new ArrayList<String>();

        if (allowHeader == null || allowHeader.isBlank()) {
            return result;
        }

        for (String part : allowHeader.split(",")) {
            var method = part.trim().toUpperCase();

            if (!method.isBlank() && !result.contains(method)) {
                result.add(method);
            }
        }

        return result;
    }

    static String shortMethod(String method) {
        if ("DELETE".equals(method)) {
            return "DEL";
        }

        if ("OPTIONS".equals(method)) {
            return "OPT";
        }

        if ("CONNECT".equals(method)) {
            return "CON";
        }

        if (method == null || method.isBlank()) {
            return "UNK";
        }

        return method.length() <= 5
                ? method
                : method.substring(0, 5);
    }

    static String tabName(
            String prefix,
            String method,
            String suffix,
            int number,
            int maxLength
    ) {
        var safePrefix = prefix == null || prefix.isBlank()
                ? "HV"
                : prefix.trim();

        var safeSuffix = suffix == null || suffix.isBlank()
                ? "CHECK"
                : suffix.trim();

        var name = safePrefix
                + "-"
                + shortMethod(method)
                + "-"
                + safeSuffix
                + "-"
                + String.format("%02d", number);

        if (name.length() <= maxLength) {
            return name;
        }

        return name.substring(0, Math.max(8, maxLength));
    }

    static boolean containsIgnoreCase(
            String source,
            String value
    ) {
        if (source == null || value == null) {
            return false;
        }

        return source.toLowerCase().contains(
                value.toLowerCase()
        );
    }
}


// ---------- Requisicao selecionada ----------

var originalRequest = requestResponse.request();

if (originalRequest == null) {
    logging.logToError(
            "Nao foi possivel obter a requisicao selecionada."
    );
    return;
}

if (originalRequest.httpService() == null) {
    logging.logToError(
            "A requisicao nao possui HttpService valido."
    );
    return;
}

var originalMethod = originalRequest.method().toUpperCase();

logging.logToOutput(
        "============================================================"
);

logging.logToOutput(
        "HTTP Verb Tester iniciado para "
                + originalMethod
                + " "
                + originalRequest.path()
);

logging.logToOutput(
        "[CONFIG] TEST_POST="
                + TEST_POST
                + " | ENABLE_STATE_CHANGING_METHODS="
                + ENABLE_STATE_CHANGING_METHODS
                + " | SKIP_ORIGINAL_METHOD="
                + SKIP_ORIGINAL_METHOD
);


// ---------- Montagem do plano ----------

List<String> configuredMethods = new ArrayList<String>();

if (TEST_GET) {
    configuredMethods.add("GET");
}

if (TEST_HEAD) {
    configuredMethods.add("HEAD");
}

if (TEST_POST) {
    configuredMethods.add("POST");
}

if (TEST_PUT) {
    configuredMethods.add("PUT");
}

if (TEST_PATCH) {
    configuredMethods.add("PATCH");
}

if (TEST_DELETE) {
    configuredMethods.add("DELETE");
}

if (TEST_OPTIONS || (CHECK_ALLOW_HEADER && FORCE_OPTIONS_FOR_ALLOW_CHECK)) {
    configuredMethods.add("OPTIONS");
}

if (TEST_TRACE) {
    configuredMethods.add("TRACE");
}

if (TEST_CONNECT) {
    configuredMethods.add("CONNECT");
}

List<String> methodsToTest = new ArrayList<String>();

for (String method : configuredMethods) {
    if (methodsToTest.contains(method)) {
        continue;
    }

    if (SKIP_ORIGINAL_METHOD && originalMethod.equals(method)) {
        continue;
    }

    if (
            VerbTools.isStateChanging(method)
                    && !ENABLE_STATE_CHANGING_METHODS
    ) {
        logging.logToOutput(
                "[BLOQUEADO] "
                        + method
                        + " exige ENABLE_STATE_CHANGING_METHODS=true"
        );

        continue;
    }

    methodsToTest.add(method);
}

if (methodsToTest.isEmpty() && !SEND_BASELINE_REQUEST) {
    logging.logToOutput(
            "Nenhum metodo ficou habilitado para teste."
    );
    return;
}

if (LOG_REQUEST_PLAN) {
    logging.logToOutput(
            "Metodo original: " + originalMethod
    );

    logging.logToOutput(
            "Metodos planejados: " + methodsToTest
    );

    logging.logToOutput(
            "Estrategia de corpo: " + BODY_STRATEGY
    );

    logging.logToOutput(
            "Modo de envio: " + REQUEST_MODE
    );

    if (!TEST_POST && "POST".equals(originalMethod)) {
        logging.logToOutput(
                "[CONFIG] POST nao sera testado porque TEST_POST=false."
        );
    } else if (
            TEST_POST
                    && "POST".equals(originalMethod)
                    && SKIP_ORIGINAL_METHOD
    ) {
        logging.logToOutput(
                "[CONFIG] POST foi habilitado, mas removido porque "
                        + "e o metodo original e SKIP_ORIGINAL_METHOD=true."
        );
    }
}


// ---------- Baseline opcional ----------

Integer baselineStatus = null;
Integer baselineLength = null;

if (SEND_BASELINE_REQUEST) {
    try {
        var baselineResult =
                api.http().sendRequest(originalRequest);

        if (
                baselineResult != null
                        && baselineResult.response() != null
        ) {
            baselineStatus =
                    (int) baselineResult.response().statusCode();

            baselineLength =
                    baselineResult.response().body().length();

            logging.logToOutput(
                    "[BASELINE] HTTP="
                            + baselineStatus
                            + " | tamanho="
                            + baselineLength
            );
        } else {
            logging.logToOutput(
                    "[BASELINE] sem resposta"
            );
        }
    } catch (Exception exception) {
        logging.logToError(
                "Falha ao enviar baseline: "
                        + exception.getMessage()
        );
    }
}


// ---------- Criacao das requisicoes ----------

List<HttpRequest> testRequests = new ArrayList<HttpRequest>();
List<String> requestMethods = new ArrayList<String>();

for (String method : methodsToTest) {
    var mutatedRequest =
            originalRequest.withMethod(method);

    mutatedRequest = VerbTools.applyBodyStrategy(
            originalRequest,
            mutatedRequest,
            method,
            BODY_STRATEGY,
            REMOVE_CONTENT_TYPE_WITH_BODY,
            REMOVE_TRANSFER_ENCODING_WITH_BODY
    );

    if ("TRACE".equals(method) && CHECK_TRACE_REFLECTION) {
        mutatedRequest = mutatedRequest.withUpdatedHeader(
                TRACE_MARKER_HEADER_NAME,
                TRACE_MARKER_VALUE
        );
    }

    testRequests.add(mutatedRequest);
    requestMethods.add(method);
}


// ---------- Envio e analise inicial ----------

List<VerbResult> results = new ArrayList<VerbResult>();
var noResponseCount = 0;

var normalizedRequestMode = REQUEST_MODE == null
        ? "SEQUENTIAL"
        : REQUEST_MODE.trim().toUpperCase();

if ("BATCH".equals(normalizedRequestMode)) {
    List<HttpRequestResponse> responses;

    try {
        responses = api.http().sendRequests(testRequests);
    } catch (Exception exception) {
        logging.logToError(
                "Falha ao enviar os testes em lote: "
                        + exception.getMessage()
        );
        return;
    }

    if (responses == null) {
        logging.logToError(
                "O Burp nao retornou a lista de respostas."
        );
        return;
    }

    for (int index = 0; index < requestMethods.size(); index++) {
        var method = requestMethods.get(index);

        if (index >= responses.size()) {
            noResponseCount++;

            logging.logToOutput(
                    "[SEM RESPOSTA] metodo="
                            + method
                            + " | resultado ausente no lote"
            );

            continue;
        }

        var result = responses.get(index);

        if (result == null || result.response() == null) {
            noResponseCount++;

            logging.logToOutput(
                    "[SEM RESPOSTA] metodo="
                            + method
                            + " | item do lote sem response"
            );

            continue;
        }

        var response = result.response();
        var status = (int) response.statusCode();
        var length = response.body().length();
        var allowHeader = response.headerValue("Allow");

        var accepted = VerbTools.isAccepted(
                status,
                ACCEPTED_STATUS_MIN,
                ACCEPTED_STATUS_MAX
        );

        var traceReflected = false;

        if (
                "TRACE".equals(method)
                        && CHECK_TRACE_REFLECTION
                        && accepted
        ) {
            var responseText = response.body().toString();

            traceReflected =
                    VerbTools.containsIgnoreCase(
                            responseText,
                            TRACE_MARKER_HEADER_NAME
                    )
                            || VerbTools.containsIgnoreCase(
                            responseText,
                            TRACE_MARKER_VALUE
                    );
        }

        results.add(
                new VerbResult(
                        method,
                        result.request(),
                        result,
                        status,
                        length,
                        allowHeader,
                        accepted,
                        traceReflected
                )
        );
    }
} else {
    // Modo recomendado: uma requisicao por vez.
    for (int index = 0; index < testRequests.size(); index++) {
        var method = requestMethods.get(index);
        var testRequest = testRequests.get(index);

        logging.logToOutput(
                "[ENVIANDO] metodo=" + method
        );

        try {
            var result = api.http().sendRequest(testRequest);

            if (result == null || result.response() == null) {
                noResponseCount++;

                logging.logToOutput(
                        "[SEM RESPOSTA] metodo="
                                + method
                                + " | sendRequest retornou sem response"
                );

                continue;
            }

            var response = result.response();
            var status = (int) response.statusCode();
            var length = response.body().length();
            var allowHeader = response.headerValue("Allow");

            var accepted = VerbTools.isAccepted(
                    status,
                    ACCEPTED_STATUS_MIN,
                    ACCEPTED_STATUS_MAX
            );

            var traceReflected = false;

            if (
                    "TRACE".equals(method)
                            && CHECK_TRACE_REFLECTION
                            && accepted
            ) {
                var responseText = response.body().toString();

                traceReflected =
                        VerbTools.containsIgnoreCase(
                                responseText,
                                TRACE_MARKER_HEADER_NAME
                        )
                                || VerbTools.containsIgnoreCase(
                                responseText,
                                TRACE_MARKER_VALUE
                        );
            }

            results.add(
                    new VerbResult(
                            method,
                            result.request(),
                            result,
                            status,
                            length,
                            allowHeader,
                            accepted,
                            traceReflected
                    )
            );

            logging.logToOutput(
                    "[RECEBIDO] metodo="
                            + method
                            + " | HTTP="
                            + status
                            + " | tamanho="
                            + length
            );
        } catch (Exception exception) {
            noResponseCount++;

            logging.logToError(
                    "[ERRO DE ENVIO] metodo="
                            + method
                            + " | "
                            + exception.getMessage()
            );
        }

        if (
                DELAY_BETWEEN_REQUESTS_MS > 0
                        && index < testRequests.size() - 1
        ) {
            try {
                Thread.sleep(DELAY_BETWEEN_REQUESTS_MS);
            } catch (InterruptedException interruptedException) {
                Thread.currentThread().interrupt();

                logging.logToError(
                        "A pausa entre requisicoes foi interrompida."
                );

                break;
            }
        }
    }
}


// ---------- Header Allow ----------

String observedAllowHeader = null;
List<String> declaredMethods = new ArrayList<String>();

if (CHECK_ALLOW_HEADER) {
    for (VerbResult result : results) {
        if (
                "OPTIONS".equals(result.method())
                        && result.allowHeader() != null
                        && !result.allowHeader().isBlank()
        ) {
            observedAllowHeader = result.allowHeader();
            declaredMethods = VerbTools.parseAllow(
                    observedAllowHeader
            );

            break;
        }
    }

    if (observedAllowHeader == null) {
        logging.logToOutput(
                "[ALLOW] OPTIONS nao retornou header Allow."
        );
    } else {
        logging.logToOutput(
                "[ALLOW] declarado: " + declaredMethods
        );
    }
}


// ---------- Classificacao e destinos ----------

var acceptedCount = 0;
var rejectedCount = 0;
var authCount = 0;
var redirectCount = 0;
var serverErrorCount = 0;
var otherCount = 0;

var allowMismatchCount = 0;
var traceReflectionCount = 0;

var repeaterSentCount = 0;
var organizerSentCount = 0;

for (VerbResult result : results) {
    var method = result.method();
    var status = result.status();

    var acceptedNotDeclared =
            CHECK_ALLOW_HEADER
                    && REPORT_ACCEPTED_NOT_IN_ALLOW
                    && result.accepted()
                    && observedAllowHeader != null
                    && !declaredMethods.contains(method);

    if (acceptedNotDeclared) {
        allowMismatchCount++;
    }

    if (result.traceReflected()) {
        traceReflectionCount++;
    }

    var shouldSendToRepeater = false;
    var sentToRepeater = false;
    var sentToOrganizer = false;
    var tabSuffix = String.valueOf(status);

    if (result.accepted()) {
        acceptedCount++;
        shouldSendToRepeater = SEND_ACCEPTED_TO_REPEATER;

        var details = "";

        if (acceptedNotDeclared) {
            details += " | nao declarado no Allow";
            tabSuffix = "ALLOW";
        }

        if (result.traceReflected()) {
            details += " | TRACE refletiu marcador";
            tabSuffix = "REFLECT";
        }

        logging.logToOutput(
                "[ACEITO] metodo="
                        + method
                        + " | HTTP="
                        + status
                        + " | tamanho="
                        + result.responseLength()
                        + details
        );

        if (
                acceptedNotDeclared
                        && SEND_ALLOW_MISMATCH_TO_REPEATER
        ) {
            shouldSendToRepeater = true;
        }
    } else if (status == 401 || status == 403) {
        authCount++;

        var reportAsSupported =
                (status == 401 && REPORT_401_AS_POSSIBLE_SUPPORTED)
                        || (status == 403 && REPORT_403_AS_POSSIBLE_SUPPORTED);

        if (LOG_AUTH_RESPONSES || reportAsSupported) {
            logging.logToOutput(
                    "[AUTORIZACAO] metodo="
                            + method
                            + " | HTTP="
                            + status
                            + (
                            reportAsSupported
                                    ? " | metodo possivelmente implementado"
                                    : ""
                    )
            );
        }

        shouldSendToRepeater =
                reportAsSupported
                        && SEND_AUTH_RESPONSES_TO_REPEATER;

        tabSuffix = "AUTH" + status;
    } else if (status >= 300 && status < 400) {
        redirectCount++;

        if (REPORT_REDIRECTS && LOG_REDIRECTS) {
            logging.logToOutput(
                    "[REDIRECT] metodo="
                            + method
                            + " | HTTP="
                            + status
            );
        }

        shouldSendToRepeater =
                REPORT_REDIRECTS
                        && SEND_REDIRECTS_TO_REPEATER;

        tabSuffix = "REDIR";
    } else if (status >= 400 && status < 500) {
        rejectedCount++;

        if (LOG_REJECTED_METHODS) {
            logging.logToOutput(
                    "[REJEITADO] metodo="
                            + method
                            + " | HTTP="
                            + status
            );
        }
    } else if (status >= 500 && status < 600) {
        serverErrorCount++;

        if (REPORT_SERVER_ERRORS && LOG_SERVER_ERRORS) {
            logging.logToOutput(
                    "[ERRO 5XX] metodo="
                            + method
                            + " | HTTP="
                            + status
            );
        }

        shouldSendToRepeater =
                REPORT_SERVER_ERRORS
                        && SEND_SERVER_ERRORS_TO_REPEATER;

        tabSuffix = "5XX";
    } else {
        otherCount++;

        if (LOG_OTHER_RESPONSES) {
            logging.logToOutput(
                    "[OUTRO] metodo="
                            + method
                            + " | HTTP="
                            + status
            );
        }
    }

    if (shouldSendToRepeater) {
        if (repeaterSentCount < MAX_REPEATER_TABS) {
            try {
                var tabName = VerbTools.tabName(
                        REPEATER_TAB_PREFIX,
                        method,
                        tabSuffix,
                        repeaterSentCount + 1,
                        REPEATER_TAB_NAME_MAX_LENGTH
                );

                api.repeater().sendToRepeater(
                        result.request(),
                        tabName
                );

                repeaterSentCount++;
                sentToRepeater = true;
            } catch (Exception exception) {
                logging.logToError(
                        "Falha ao enviar "
                                + method
                                + " ao Repeater: "
                                + exception.getMessage()
                );
            }
        } else if (SEND_EXCESS_TO_ORGANIZER) {
            try {
                api.organizer().sendToOrganizer(
                        result.request()
                );

                organizerSentCount++;
                sentToOrganizer = true;
            } catch (Exception exception) {
                logging.logToError(
                        "Falha ao enviar "
                                + method
                                + " ao Organizer: "
                                + exception.getMessage()
                );
            }
        }
    }

    /*
     * Opcao geral:
     * tudo que foi executado e nao gerou aba no Repeater vai para
     * o Organizer, desde que ainda nao tenha sido enviado para la.
     */
    if (
            SEND_NON_REPEATER_RESULTS_TO_ORGANIZER
                    && !sentToRepeater
                    && !sentToOrganizer
    ) {
        try {
            api.organizer().sendToOrganizer(
                    result.request()
            );

            organizerSentCount++;
            sentToOrganizer = true;

            logging.logToOutput(
                    "[ORGANIZER] metodo="
                            + method
                            + " | HTTP="
                            + status
                            + " | motivo=nao enviado ao Repeater"
            );
        } catch (Exception exception) {
            logging.logToError(
                    "Falha ao enviar "
                            + method
                            + " ao Organizer: "
                            + exception.getMessage()
            );
        }
    }
}


// ---------- Metodos do Allow nao testados ----------

if (
        CHECK_ALLOW_HEADER
                && LOG_UNTESTED_ALLOW_METHODS
                && !declaredMethods.isEmpty()
) {
    for (String declaredMethod : declaredMethods) {
        if (!requestMethods.contains(declaredMethod)) {
            logging.logToOutput(
                    "[ALLOW NAO TESTADO] metodo="
                            + declaredMethod
            );
        }
    }
}


// ---------- Resumo ----------

logging.logToOutput(
        "------------------------------------------------------------"
);

logging.logToOutput(
        String.format(
                "Resumo: %d aceitos | %d rejeitados | %d auth | "
                        + "%d redirects | %d erros 5xx | %d outros "
                        + "| %d sem resposta",
                acceptedCount,
                rejectedCount,
                authCount,
                redirectCount,
                serverErrorCount,
                otherCount,
                noResponseCount
        )
);

logging.logToOutput(
        String.format(
                "Analise adicional: %d aceitos fora do Allow "
                        + "| %d reflexoes TRACE",
                allowMismatchCount,
                traceReflectionCount
        )
);

logging.logToOutput(
        String.format(
                "Destino: %d enviados ao Repeater "
                        + "| %d enviados ao Organizer "
                        + "| fallback Organizer=%s",
                repeaterSentCount,
                organizerSentCount,
                SEND_NON_REPEATER_RESULTS_TO_ORGANIZER
        )
);

logging.logToOutput(
        "============================================================"
);
