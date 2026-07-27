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
// ================= BASIC USAGE =================
// ------------------------------------------------------------
// Paste this script in:
// Repeater > Custom actions > New > Blank
//
// Execute only on systems you have authorization to test.
//
// Objective:
//  - Create variations of the current request using other HTTP methods.
//  - Identify alternative methods that return statuses configured
//    as "accepted".
//  - Send relevant results to the Repeater.
//
// Observation:
//  - An alternative method returning 2xx does not confirm a vulnerability.
//    The result means "method accepted and should be reviewed".
//  - Methods that change state are disabled by default.
// ============================================================


// ================= USER CONFIGURATION =================
//
// Change only this section to customize execution.


// ---------- Tested methods ----------

var TEST_GET = true;
var TEST_HEAD = true;
var TEST_POST = false;
var TEST_PUT = false;
var TEST_PATCH = false;
var TEST_DELETE = false;
var TEST_OPTIONS = true;
var TEST_TRACE = true;

// CONNECT is disabled by default.
// It may have special behavior on proxies and servers.
var TEST_CONNECT = false;


// ---------- Original method ----------
//
// TEST_POST=true only enables POST in the list.
// If the original request is already POST and SKIP_ORIGINAL_METHOD=true,
// it will still be removed from the plan.
//
// To re-test the original method:
//   TEST_POST = true;
//   SKIP_ORIGINAL_METHOD = false;

var SKIP_ORIGINAL_METHOD = true;


// ---------- Submission mode ----------
//
// "SEQUENTIAL" is the recommended mode for Custom Actions.
// Each request is sent individually and its response is analyzed.
//
// "BATCH" uses sendRequests(). It can be faster, but some versions
// or configurations of Burp may return items without a response.

var REQUEST_MODE = "SEQUENTIAL";

// Optional interval between requests in sequential mode.
var DELAY_BETWEEN_REQUESTS_MS = 0;


// ---------- State change protection ----------
//
// When false, POST, PUT, PATCH, and DELETE will not be sent,
// even if individual tests above are enabled.

var ENABLE_STATE_CHANGING_METHODS = true;


// ---------- Body strategy ----------
//
// Allowed values:
//
// "AUTO"
//   GET, HEAD, OPTIONS, TRACE and CONNECT: removes the body.
//   POST, PUT, PATCH and DELETE: keeps the original body.
//
// "KEEP"
//   Keeps the original body for all methods.
//
// "REMOVE"
//   Removes the body for all methods.

var BODY_STRATEGY = "AUTO";

// Removes Content-Type when the body is removed.
var REMOVE_CONTENT_TYPE_WITH_BODY = true;

// Removes Transfer-Encoding when the body is removed.
var REMOVE_TRANSFER_ENCODING_WITH_BODY = true;


// ---------- Accepted method criteria ----------
//
// Any response in this range will be treated as an accepted method.

var ACCEPTED_STATUS_MIN = 200;
var ACCEPTED_STATUS_MAX = 299;


// ---------- Authentication/Authorization handling ----------
//
// 401 and 403 can indicate the method exists, but requires credentials
// or different privileges. By default they are only logged.

var REPORT_401_AS_POSSIBLE_SUPPORTED = true;
var REPORT_403_AS_POSSIBLE_SUPPORTED = true;

var SEND_AUTH_RESPONSES_TO_REPEATER = false;


// ---------- Redirects ----------
//
// HTTP submission in Montoya API follows Burp's configuration.
// This option only controls the classification of the received result.

var REPORT_REDIRECTS = true;
var SEND_REDIRECTS_TO_REPEATER = false;


// ---------- Server errors ----------

var REPORT_SERVER_ERRORS = true;
var SEND_SERVER_ERRORS_TO_REPEATER = false;


// ---------- OPTIONS / Allow ----------

var CHECK_ALLOW_HEADER = true;

// When true, OPTIONS is added to the plan even if TEST_OPTIONS=false.
var FORCE_OPTIONS_FOR_ALLOW_CHECK = true;

// Reports methods that returned 2xx, but do not appear in Allow.
var REPORT_ACCEPTED_NOT_IN_ALLOW = true;

// Reports methods declared in Allow, but that were not tested.
var LOG_UNTESTED_ALLOW_METHODS = true;

// Sends to Repeater an accepted method that was not declared in Allow.
var SEND_ALLOW_MISMATCH_TO_REPEATER = true;


// ---------- TRACE ----------
//
// When TRACE returns 2xx, checks if parts of the request
// appear reflected in the response.

var CHECK_TRACE_REFLECTION = true;

// Text searched in the TRACE response.
// The script adds this header only in the TRACE request.
var TRACE_MARKER_HEADER_NAME = "X-HTTP-Verb-Test";
var TRACE_MARKER_VALUE = "burp-http-verb-check";


// ---------- Optional baseline ----------
//
// Resending the original request might repeat an operation.
// Therefore, this is disabled by default.

var SEND_BASELINE_REQUEST = false;


// ---------- Destinations ----------

var SEND_ACCEPTED_TO_REPEATER = true;
var SEND_EXCESS_TO_ORGANIZER = true;

// Sends to Organizer every executed request that did not generate
// a tab in Repeater.
//
// Includes, according to the result:
// - rejected methods
// - 401/403 responses
// - redirects
// - 5xx errors
// - other statuses
//
// Requests without a response cannot be sent to Organizer,
// since there is no complete HttpRequestResponse to store.
var SEND_NON_REPEATER_RESULTS_TO_ORGANIZER = true;

var MAX_REPEATER_TABS = 12;

// Short prefix for tab names.
var REPEATER_TAB_PREFIX = "HV";

// Maximum length for tab name.
var REPEATER_TAB_NAME_MAX_LENGTH = 22;


// ---------- Logs ----------

var LOG_REJECTED_METHODS = true;
var LOG_REDIRECTS = true;
var LOG_AUTH_RESPONSES = true;
var LOG_SERVER_ERRORS = true;
var LOG_OTHER_RESPONSES = true;
var LOG_REQUEST_PLAN = true;


// ============================================================
// END OF CONFIGURATION
// ============================================================


// ---------- Auxiliary types ----------

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


// ---------- Selected request ----------

var originalRequest = requestResponse.request();

if (originalRequest == null) {
    logging.logToError(
            "Could not get the selected request."
    );
    return;
}

if (originalRequest.httpService() == null) {
    logging.logToError(
            "Request does not have a valid HttpService."
    );
    return;
}

var originalMethod = originalRequest.method().toUpperCase();

logging.logToOutput(
        "============================================================"
);

logging.logToOutput(
        "HTTP Verb Tester started for "
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


// ---------- Plan assembly ----------

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
                "[BLOCKED] "
                        + method
                        + " requires ENABLE_STATE_CHANGING_METHODS=true"
        );

        continue;
    }

    methodsToTest.add(method);
}

if (methodsToTest.isEmpty() && !SEND_BASELINE_REQUEST) {
    logging.logToOutput(
            "No methods were enabled for testing."
    );
    return;
}

if (LOG_REQUEST_PLAN) {
    logging.logToOutput(
            "Original method: " + originalMethod
    );

    logging.logToOutput(
            "Planned methods: " + methodsToTest
    );

    logging.logToOutput(
            "Body strategy: " + BODY_STRATEGY
    );

    logging.logToOutput(
            "Submission mode: " + REQUEST_MODE
    );

    if (!TEST_POST && "POST".equals(originalMethod)) {
        logging.logToOutput(
                "[CONFIG] POST will not be tested because TEST_POST=false."
        );
    } else if (
            TEST_POST
                    && "POST".equals(originalMethod)
                    && SKIP_ORIGINAL_METHOD
    ) {
        logging.logToOutput(
                "[CONFIG] POST was enabled, but removed because "
                        + "it is the original method and SKIP_ORIGINAL_METHOD=true."
        );
    }
}


// ---------- Optional baseline ----------

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
                            + " | size="
                            + baselineLength
            );
        } else {
            logging.logToOutput(
                    "[BASELINE] no response"
            );
        }
    } catch (Exception exception) {
        logging.logToError(
                "Failed to send baseline: "
                        + exception.getMessage()
        );
    }
}


// ---------- Request creation ----------

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


// ---------- Submission and initial analysis ----------

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
                "Failed to send batch tests: "
                        + exception.getMessage()
        );
        return;
    }

    if (responses == null) {
        logging.logToError(
                "Burp did not return the list of responses."
        );
        return;
    }

    for (int index = 0; index < requestMethods.size(); index++) {
        var method = requestMethods.get(index);

        if (index >= responses.size()) {
            noResponseCount++;

            logging.logToOutput(
                    "[NO RESPONSE] method="
                            + method
                            + " | result missing in batch"
            );

            continue;
        }

        var result = responses.get(index);

        if (result == null || result.response() == null) {
            noResponseCount++;

            logging.logToOutput(
                    "[NO RESPONSE] method="
                            + method
                            + " | batch item without response"
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
    // Recommended mode: one request at a time.
    for (int index = 0; index < testRequests.size(); index++) {
        var method = requestMethods.get(index);
        var testRequest = testRequests.get(index);

        logging.logToOutput(
                "[SENDING] method=" + method
        );

        try {
            var result = api.http().sendRequest(testRequest);

            if (result == null || result.response() == null) {
                noResponseCount++;

                logging.logToOutput(
                        "[NO RESPONSE] method="
                                + method
                                + " | sendRequest returned without response"
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
                    "[RECEIVED] method="
                            + method
                            + " | HTTP="
                            + status
                            + " | size="
                            + length
            );
        } catch (Exception exception) {
            noResponseCount++;

            logging.logToError(
                    "[SEND ERROR] method="
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
                        "Pause between requests was interrupted."
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
                        + " | size="
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
