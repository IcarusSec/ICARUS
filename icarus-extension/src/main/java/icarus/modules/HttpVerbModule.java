package icarus.modules;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import icarus.core.Category;
import icarus.core.Finding;
import icarus.core.IcarusModule;
import icarus.core.ModuleConfig;
import icarus.core.Severity;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class HttpVerbModule implements IcarusModule {

    private final MontoyaApi api;

    public HttpVerbModule(MontoyaApi api) {
        this.api = api;
    }

    @Override
    public String name() {
        return "HTTP Verb Tester";
    }

    @Override
    public List<Finding> run(HttpRequestResponse requestResponse, ModuleConfig config, Consumer<String> logger) {
        if (!config.getBool("hv.enabled", true)) {
            return List.of();
        }

        HttpRequest originalRequest = requestResponse.request();
        if (originalRequest == null || originalRequest.httpService() == null) {
            return List.of();
        }

        String originalMethod = originalRequest.method().toUpperCase();
        List<String> methodsToTest = assemblePlan(originalMethod, config);

        if (methodsToTest.isEmpty()) {
            return List.of();
        }

        int delayMs = config.getInt("hv.delay_ms", 0);
        List<VerbResult> results = new ArrayList<>();

        for (int i = 0; i < methodsToTest.size(); i++) {
            icarus.ScanRunner.waitIfPaused();
            if (Thread.currentThread().isInterrupted()) {
                logger.accept("Stopped by user — " + (methodsToTest.size() - i) + " verb(s) skipped.");
                break;
            }
            String method = methodsToTest.get(i);
            HttpRequest mutated = applyBodyStrategy(originalRequest, method, config);

            if ("TRACE".equals(method) && config.getBool("hv.check_trace_reflection", true)) {
                mutated = mutated.withUpdatedHeader("X-HTTP-Verb-Test", "burp-http-verb-check");
            }

            try {
                HttpRequestResponse result = api.http().sendRequest(mutated);
                if (result != null && result.response() != null) {
                    results.add(analyzeResult(method, result, config));
                }
            } catch (Exception e) {
                api.logging().logToError("HTTP Verb Tester: request failed for " + method + ": " + e);
            }

            if (delayMs > 0 && i < methodsToTest.size() - 1) {
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        return generateFindings(results, config);
    }

    private List<String> assemblePlan(String originalMethod, ModuleConfig config) {
        List<String> configured = new ArrayList<>();
        if (config.getBool("hv.test_get", true)) configured.add("GET");
        if (config.getBool("hv.test_head", true)) configured.add("HEAD");
        if (config.getBool("hv.test_post", false)) configured.add("POST");
        if (config.getBool("hv.test_put", false)) configured.add("PUT");
        if (config.getBool("hv.test_patch", false)) configured.add("PATCH");
        if (config.getBool("hv.test_delete", false)) configured.add("DELETE");

        boolean forceOptions = config.getBool("hv.force_options_for_allow", true) && config.getBool("hv.check_allow", true);
        if (config.getBool("hv.test_options", true) || forceOptions) configured.add("OPTIONS");
        if (config.getBool("hv.test_trace", true)) configured.add("TRACE");
        if (config.getBool("hv.test_connect", false)) configured.add("CONNECT");

        boolean skipOriginal = config.getBool("hv.skip_original", true);
        boolean enableStateChanging = config.getBool("hv.enable_state_changing", true);

        return configured.stream()
                .filter(m -> !skipOriginal || !m.equals(originalMethod))
                .filter(m -> enableStateChanging || !isStateChanging(m))
                .distinct()
                .toList();
    }

    private HttpRequest applyBodyStrategy(HttpRequest original, String method, ModuleConfig config) {
        HttpRequest mutated = original.withMethod(method);
        String strategy = config.getString("hv.body_strategy", "AUTO").toUpperCase();

        boolean removeBody = switch (strategy) {
            case "REMOVE" -> true;
            case "KEEP" -> false;
            default -> !normallyHasBody(method); // AUTO
        };

        if (!removeBody) {
            return mutated.withBody(original.body());
        }

        mutated = mutated.withBody("");
        if (config.getBool("hv.remove_content_type_with_body", true)) {
            mutated = mutated.withRemovedHeader("Content-Type");
        }
        if (config.getBool("hv.remove_transfer_encoding_with_body", true)) {
            mutated = mutated.withRemovedHeader("Transfer-Encoding");
        }
        return mutated;
    }

    private VerbResult analyzeResult(String method, HttpRequestResponse r, ModuleConfig config) {
        HttpResponse response = r.response();
        int status = response.statusCode();
        int min = config.getInt("hv.accepted_min", 200);
        int max = config.getInt("hv.accepted_max", 299);
        boolean accepted = status >= min && status <= max;

        boolean traceReflected = false;
        if ("TRACE".equals(method) && config.getBool("hv.check_trace_reflection", true) && accepted) {
            String body = response.bodyToString().toLowerCase();
            traceReflected = body.contains("x-http-verb-test") || body.contains("burp-http-verb-check");
        }

        return new VerbResult(
            method,
            r,
            status,
            response.headerValue("Allow"),
            accepted,
            traceReflected
        );
    }

    private List<Finding> generateFindings(List<VerbResult> results, ModuleConfig config) {
        List<Finding> findings = new ArrayList<>();

        List<String> allowedMethods = new ArrayList<>();
        if (config.getBool("hv.check_allow", true)) {
            results.stream()
                    .filter(r -> "OPTIONS".equals(r.method()) && r.allowHeader() != null && !r.allowHeader().isBlank())
                    .findFirst()
                    .ifPresent(r -> allowedMethods.addAll(parseAllow(r.allowHeader())));
        }

        for (VerbResult result : results) {
            String method = result.method();
            int status = result.status();

            if (result.accepted()) {
                findings.add(Finding.builder(name(), "ACCEPTED_METHOD")
                        .description("Method " + method + " was accepted with status " + status)
                        .severity(Severity.MEDIUM)
                        .category(Category.HTTP_METHOD)
                        .evidence(result.evidence())
                        .meta("method", method)
                        .meta("status", String.valueOf(status))
                        .build());

                if (config.getBool("hv.check_allow", true) && config.getBool("hv.report_accepted_not_in_allow", true)) {
                    if (!allowedMethods.isEmpty() && !allowedMethods.contains(method)) {
                        findings.add(Finding.builder(name(), "ALLOW_MISMATCH")
                                .description("Method " + method + " was accepted but is not in the Allow header.")
                                .severity(Severity.MEDIUM)
                                .category(Category.HTTP_METHOD)
                                .evidence(result.evidence())
                                .meta("method", method)
                                .build());
                    }
                }
            }

            if (result.traceReflected()) {
                findings.add(Finding.builder(name(), "TRACE_REFLECTION")
                        .description("TRACE request reflected injected marker header in response body.")
                        .severity(Severity.HIGH)
                        .category(Category.HTTP_METHOD)
                        .evidence(result.evidence())
                        .build());
            }

            if (status == 401 || status == 403) {
                boolean report401 = status == 401 && config.getBool("hv.report_401", true);
                boolean report403 = status == 403 && config.getBool("hv.report_403", true);
                if (report401 || report403) {
                    findings.add(Finding.builder(name(), "AUTH_REQUIRED")
                            .description("Method " + method + " requires authentication/authorization (" + status + ")")
                            .severity(Severity.LOW)
                            .category(Category.HTTP_METHOD)
                            .evidence(result.evidence())
                            .meta("method", method)
                            .build());
                }
            }

            if (status >= 300 && status < 400 && config.getBool("hv.report_redirects", true)) {
                findings.add(Finding.builder(name(), "REDIRECT")
                        .description("Method " + method + " resulted in a redirect (" + status + ")")
                        .severity(Severity.INFO)
                        .category(Category.HTTP_METHOD)
                        .evidence(result.evidence())
                        .meta("method", method)
                        .build());
            }

            if (status >= 500 && status < 600 && config.getBool("hv.report_server_errors", true)) {
                findings.add(Finding.builder(name(), "SERVER_ERROR")
                        .description("Method " + method + " resulted in a server error (" + status + ")")
                        .severity(Severity.INFO)
                        .category(Category.HTTP_METHOD)
                        .evidence(result.evidence())
                        .meta("method", method)
                        .build());
            }
        }

        return findings;
    }

    private boolean isStateChanging(String method) {
        return Set.of("POST", "PUT", "PATCH", "DELETE").contains(method);
    }

    private boolean normallyHasBody(String method) {
        return Set.of("POST", "PUT", "PATCH", "DELETE").contains(method);
    }

    private List<String> parseAllow(String allowHeader) {
        if (allowHeader == null || allowHeader.isBlank()) return List.of();
        return Arrays.stream(allowHeader.split(","))
                .map(String::trim)
                .map(String::toUpperCase)
                .filter(s -> !s.isBlank())
                .distinct()
                .toList();
    }

    private record VerbResult(
        String method,
        HttpRequestResponse evidence,
        int status,
        String allowHeader,
        boolean accepted,
        boolean traceReflected
    ) {}
}
