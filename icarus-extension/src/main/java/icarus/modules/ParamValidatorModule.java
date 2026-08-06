package icarus.modules;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import icarus.core.Category;
import icarus.core.Finding;
import icarus.core.IcarusModule;
import icarus.core.JsonParser;
import icarus.core.JsonPaths;
import icarus.core.ModuleConfig;
import icarus.core.RawNumber;
import icarus.core.Severity;
import icarus.core.VerboseErrorDetector;

import java.util.*;
import java.util.function.Consumer;

public final class ParamValidatorModule implements IcarusModule {

    private final MontoyaApi api;

    public ParamValidatorModule(MontoyaApi api) {
        this.api = api;
    }

    @Override
    public String name() {
        return "ParamValidator";
    }

    record MutationSpec(String type, String description, Object value, boolean remove, Category category) {}
    record Mutation(String path, String type, String description, Category category, String body, Object value) {}

    @Override
    public List<Finding> run(HttpRequestResponse requestResponse, ModuleConfig config, Consumer<String> logger) {
        HttpRequest request = requestResponse.request();
        String contentType = request.headerValue("Content-Type");
        String originalBody = request.bodyToString();

        boolean looksLikeJson = (contentType != null && contentType.toLowerCase().contains("json"))
                || (originalBody != null && (originalBody.trim().startsWith("{") || originalBody.trim().startsWith("[")));

        if (originalBody == null || originalBody.isBlank() || !looksLikeJson) {
            return List.of();
        }

        Object originalRoot = JsonParser.parse(originalBody);
        List<List<Object>> allPaths = JsonPaths.collect(originalRoot);

        PathRules pathRules = new PathRules(
                config.getStringList("pv.include_paths"),
                config.getStringList("pv.exclude_paths"),
                config.getStringList("pv.path_exceptions")
        );

        List<List<Object>> eligiblePaths = new ArrayList<>();
        for (List<Object> path : allPaths) {
            String pathString = JsonPaths.pathToString(path);
            if (!pathRules.isIncluded(pathString)) continue;
            if (pathRules.isExcluded(pathString)) continue;
            eligiblePaths.add(path);
        }

        logger.accept("Detected " + eligiblePaths.size() + " inputs on body.");

        int maxMutations = config.getInt("pv.max_mutations", 60);
        List<Mutation> mutations = new ArrayList<>();
        
        outer:
        for (List<Object> path : eligiblePaths) {
            String pathString = JsonPaths.pathToString(path);
            Object leafValue = JsonPaths.getAt(JsonParser.parse(originalBody), path);
            
            for (MutationSpec spec : SpecsFactory.specsFor(leafValue, config)) {
                if (pathRules.isException(pathString, spec)) continue;
                if (mutations.size() >= maxMutations) break outer;
                
                Object clonedRoot = JsonParser.parse(originalBody);
                boolean applied = JsonPaths.applyAt(clonedRoot, path, spec.value(), spec.remove());
                if (applied) {
                    mutations.add(new Mutation(
                            pathString,
                            spec.type(),
                            spec.description(),
                            spec.category(),
                            JsonParser.write(clonedRoot),
                            spec.value()
                    ));
                }
            }
        }

        if (mutations.isEmpty()) {
            return List.of();
        }

        boolean requireBaseline = config.getBool("pv.require_baseline", true);
        int baselineStatusMin = 200;
        int baselineStatusMax = 299;
        int baselineLength = -1;

        if (requireBaseline) {
            HttpRequestResponse baselineResult = api.http().sendRequest(request);
            if (baselineResult == null || baselineResult.response() == null) {
                return List.of();
            }
            int baselineStatus = baselineResult.response().statusCode();
            baselineLength = baselineResult.response().body().length();
            
            if (baselineStatus < baselineStatusMin || baselineStatus > baselineStatusMax) {
                return List.of();
            }
        } else {
            if (requestResponse.response() != null) {
                baselineLength = requestResponse.response().body().length();
            }
        }

        mutations.sort((a, b) -> {
            boolean aIsTime = a.type().equals("STRING_SQLI_TIME");
            boolean bIsTime = b.type().equals("STRING_SQLI_TIME");
            if (aIsTime && !bIsTime) return 1;
            if (!aIsTime && bIsTime) return -1;
            return 0;
        });

        List<HttpRequest> mutatedRequests = new ArrayList<>();
        for (Mutation m : mutations) {
            mutatedRequests.add(request.withBody(m.body()));
        }

        long[] requestTimes = new long[mutatedRequests.size()];
        List<HttpRequestResponse> responses = new ArrayList<>();

        boolean promptedThrottle = false;
        int delayMs = 0;

        for (int i = 0; i < mutatedRequests.size(); i++) {
            if (Thread.currentThread().isInterrupted()) {
                logger.accept("Stopped by user — " + (mutatedRequests.size() - i) + " mutation(s) skipped.");
                break;
            }
            Mutation m = mutations.get(i);
            logger.accept("testing " + shortPath(m.path()) + " with " + m.description().toLowerCase() + "...");

            if (delayMs > 0) {
                try { Thread.sleep(delayMs); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }

            long startTime = System.currentTimeMillis();
            try {
                HttpRequestResponse result = api.http().sendRequest(mutatedRequests.get(i));
                responses.add(result);
                if (result != null && result.response() != null) {
                    int st = result.response().statusCode();
                    if (st == 401 || st == 403) {
                        logger.accept("tool is returning " + st);
                        if (!promptedThrottle) {
                            promptedThrottle = true;
                            // Blocking dialog must run on the EDT (CLAUDE.md) — same invokeAndWait
                            // pattern ScanRunner already uses for its Akamai prompt.
                            int[] choiceHolder = { javax.swing.JOptionPane.NO_OPTION };
                            try {
                                javax.swing.SwingUtilities.invokeAndWait(() -> choiceHolder[0] = javax.swing.JOptionPane.showConfirmDialog(null,
                                    "WAF Block (" + st + ") detected! Slow down requests (add 2s delay)?",
                                    "WAF Block Detected", javax.swing.JOptionPane.YES_NO_OPTION, javax.swing.JOptionPane.WARNING_MESSAGE));
                            } catch (InterruptedException ex) {
                                Thread.currentThread().interrupt();
                            } catch (java.lang.reflect.InvocationTargetException ex) {
                                api.logging().logToError("Param Validator: WAF throttle dialog failed: " + ex.getCause());
                            }
                            if (choiceHolder[0] == javax.swing.JOptionPane.YES_OPTION) {
                                delayMs = 2000;
                            }
                        }
                    }
                }
            } catch (Exception e) {
                api.logging().logToError("Param Validator: mutation request failed: " + e);
                logger.accept("request failed: " + e.getMessage());
                responses.add(null);
            }
            requestTimes[i] = System.currentTimeMillis() - startTime;
        }

        int findingStatusMin = config.getInt("pv.finding_status_min", 200);
        int findingStatusMax = config.getInt("pv.finding_status_max", 299);
        boolean filterExactMatch = config.getBool("pv.filter_exact_match", false);
        boolean checkXssReflection = config.getBool("pv.check_xss_reflection", true);
        int timeDelayMs = config.getInt("pv.payload_sqli_time_delay_ms", 10000);

        boolean behavioralAnalysis = config.getBool("pv.behavioral_analysis", false);
        long baselineTime = 0;
        String baselineBodyLower = "";
        if (requireBaseline) {
            long st = System.currentTimeMillis();
            try {
                HttpRequestResponse bl = api.http().sendRequest(request);
                if (bl != null && bl.response() != null) baselineBodyLower = bl.response().bodyToString().toLowerCase();
            } catch (Exception e) {
                api.logging().logToError("Param Validator: baseline request failed: " + e);
            }
            baselineTime = System.currentTimeMillis() - st;
        }

        List<Finding> findings = new ArrayList<>();
        Map<String, List<MutationResult>> groupedByPath = new LinkedHashMap<>();

        int analyzedCount = Math.min(mutations.size(), responses.size());
        for (int i = 0; i < analyzedCount; i++) {
            Mutation mutation = mutations.get(i);
            HttpRequestResponse mutatedResult = responses.get(i);

            if (mutatedResult == null || mutatedResult.response() == null) continue;

            HttpResponse mutatedResponse = mutatedResult.response();
            int status = mutatedResponse.statusCode();
            int length = mutatedResponse.body().length();
            long responseTime = requestTimes[i];
            String bodyStr = mutatedResponse.bodyToString();

            // Robust rejection of 401/403 (WAF/Auth block fix) — checked BEFORE injection
            // detection so a WAF block page can't be mistaken for a confirmed injection hit
            // just because it echoes the payload back or mentions a DB error keyword.
            if (status == 401 || status == 403) {
                continue;
            }

            // Any other 4xx is an expected application-level rejection (400/404/422 etc.),
            // not a WAF block — only the generic size/time drift heuristic below is gated on
            // it. Time-based SQLi, XSS reflection, and the CWE-209 verbose-error check still
            // run on 4xx responses since apps commonly leak SQL/stack errors on validation
            // failure pages coded 400/422, not just 500.
            boolean isExpectedRejection = status >= 400 && status <= 499;

            // ── Injection Context Extraction Engine ──
            String extractedContext = "";
            boolean isInjectionFinding = false;
            String injectionDesc = "";
            Severity injectionSeverity = Severity.HIGH;

            boolean timeDelayHit = mutation.type().equals("STRING_SQLI_TIME") && responseTime >= timeDelayMs;
            if (timeDelayHit) {
                isInjectionFinding = true;
                injectionDesc = "Time-based SQLi anomaly detected. Baseline was " + baselineTime + "ms, payload took " + responseTime + "ms.";
            }

            boolean xssReflectionHit = false;
            if (checkXssReflection && mutation.type().equals("STRING_XSS") && mutation.value() instanceof String payload) {
                if (bodyStr.contains(payload)) {
                    xssReflectionHit = true;
                    isInjectionFinding = true;
                    extractedContext = extractContext(bodyStr, payload, 60);
                    injectionDesc = "XSS Payload reflection detected. Context:\n`" + extractedContext + "`";
                }
            }

            // Weaker signal than a confirmed time delay or reflection — tiered to MEDIUM below.
            // Reuses VerboseErrorDetector (already imported, centralized in 763efe4) instead of a
            // second, weaker hardcoded signature list — same class the drift check below leans on.
            boolean backendErrorHit = false;
            // Gate the whole block on !isInjectionFinding — a confirmed HIGH hit above
            // (time delay, XSS reflection) must not get downgraded to MEDIUM just because
            // the same payload also happens to shift body size or trip an error pattern.
            if (behavioralAnalysis && !isInjectionFinding) {
                String verboseMatch = VerboseErrorDetector.getVerboseErrorMatch(bodyStr);
                if (verboseMatch != null) {
                    // Lazy, same as before: only check the baseline once we actually have
                    // a candidate match, not on every mutation.
                    boolean baselineHasError = requireBaseline && VerboseErrorDetector.getVerboseErrorMatch(baselineBodyLower) != null;
                    if (!baselineHasError) {
                        backendErrorHit = true;
                        isInjectionFinding = true;
                        injectionSeverity = Severity.MEDIUM;
                        injectionDesc = "Backend Error / SQLi Anomaly detected. " + verboseMatch;
                    }
                }

                // Generic behavioral drift (size/time) — preserved from the original
                // pre-rewrite logic, don't drop it. Even weaker signal than a DB error
                // keyword: flags "something's different", not specifically an injection.
                // Skipped on 4xx: an app's own rejection page naturally differs in size/timing
                // from the 2xx baseline, which was firing false anomalies on every 400/404/422.
                if (!backendErrorHit && !isExpectedRejection) {
                    double diffRatio = baselineLength <= 0 ? 0 : Math.abs(length - baselineLength) / (double) baselineLength;
                    if (diffRatio > 0.20) {
                        isInjectionFinding = true;
                        injectionSeverity = Severity.MEDIUM;
                        injectionDesc = "Behavioral size anomaly detected (" + length + " bytes vs baseline " + baselineLength + " bytes).";
                    } else if (baselineTime > 0 && responseTime > baselineTime * 5 && responseTime > 3000) {
                        isInjectionFinding = true;
                        injectionSeverity = Severity.MEDIUM;
                        injectionDesc = "Behavioral time anomaly detected (" + responseTime + "ms vs baseline " + baselineTime + "ms).";
                    }
                }
            }

            // Immediately spin out dedicated Injection Findings for pentester review.
            // Named by mutation.type() (e.g. STRING_XSS, STRING_SQLI_TIME) so distinct
            // injection classes on the same parameter don't collide under one shared name.
            if (isInjectionFinding) {
                findings.add(Finding.builder(name(), mutation.type())
                        .description("Manual Evaluation Required. Injection anomaly detected on parameter `" + mutation.path() + "` using payload `" + mutation.value() + "`.\n\n" + injectionDesc)
                        .severity(injectionSeverity)
                        .category(mutation.category())
                        .path(mutation.path())
                        .evidence(mutatedResult)
                        .meta("status", String.valueOf(status))
                        .meta("length", String.valueOf(length))
                        .meta("responseTime", String.valueOf(responseTime))
                        .meta("context", extractedContext)
                        .build());
                logger.accept("[FINDING] " + shortPath(mutation.path()) + " → " + mutation.type() + " (HTTP " + status + ")");
                continue; // Skip adding to the grouped validation bucket
            }

            // ── Standard Validation Logic ──
            boolean accepted = (status >= findingStatusMin && status <= findingStatusMax);

            if (accepted && filterExactMatch && length == baselineLength) {
                accepted = false;
            }

            if (accepted) {
                Severity severity = Severity.MEDIUM;
                String findingDesc = mutation.description() + " | HTTP=" + status + " | size=" + length;

                // Group standard missing-validation findings
                groupedByPath.computeIfAbsent(mutation.path(), k -> new ArrayList<>())
                             .add(new MutationResult(mutation, mutatedResult, severity, findingDesc, status, length, responseTime));
            }
        }

        // Intelligent Finding Synthesis (For Standard Validation)
        for (var entry : groupedByPath.entrySet()) {
            String path = entry.getKey();
            List<MutationResult> pathFindings = entry.getValue();

            boolean structuralFailure = false;
            boolean typeFailure = false;
            boolean boundaryFailure = false;

            for (MutationResult res : pathFindings) {
                Category cat = res.mutation().category();
                if (cat == Category.STRUCTURAL) structuralFailure = true;
                else if (cat == Category.TYPE_CONFUSION) typeFailure = true;
                else if (cat == Category.BOUNDARY) boundaryFailure = true;
            }

            // Worst-first priority: an omitted/nulled field beats a type mismatch, which
            // beats an out-of-range value. Drives both the finding's Category tag and
            // which mutation's response we show as evidence.
            Category worstCategory = structuralFailure ? Category.STRUCTURAL
                    : typeFailure ? Category.TYPE_CONFUSION
                    : Category.BOUNDARY;

            MutationResult worstEvidence = pathFindings.stream()
                    .filter(r -> r.mutation().category() == worstCategory)
                    .findFirst()
                    .orElse(pathFindings.get(0));

            String findingName = "Missing Input Validation";
            StringBuilder desc = new StringBuilder("The parameter `" + path + "` lacks comprehensive input validation. Based on the automated tests, the endpoint accepted:\n\n");

            if (structuralFailure) {
                desc.append("- **Missing Structural Enforcement:** The endpoint accepted requests where this parameter was completely omitted, set to null, or replaced with empty objects/arrays.\n");
            }
            if (typeFailure) {
                desc.append("- **Type Confusion:** The endpoint accepted data types that violate the expected schema (e.g., accepting strings instead of booleans/integers).\n");
            }
            if (boundaryFailure) {
                desc.append("- **Missing Boundary Checks:** The endpoint accepted extreme boundary values (e.g., negative numbers, massive strings, or zero).\n");
            }

            // Per-mutation detail — keeps the specific payload/status/size a pentester
            // needs to reproduce each bypass, instead of only the rolled-up summary above.
            desc.append("\nContributing payloads:\n");
            for (MutationResult res : pathFindings) {
                desc.append("- `").append(res.mutation().type()).append("`: ").append(res.desc()).append("\n");
            }

            findings.add(Finding.builder(name(), findingName)
                .description(desc.toString())
                .severity(Severity.MEDIUM)
                .category(worstCategory)
                .path(path)
                .evidence(worstEvidence.evidence())
                .meta("status", String.valueOf(worstEvidence.status()))
                .meta("length", String.valueOf(worstEvidence.length()))
                .build());
        }

        return findings;
    }

    private static String shortPath(String path) {
        return path.startsWith("$.") ? path.substring(2) : path;
    }

    private record MutationResult(Mutation mutation, HttpRequestResponse evidence, Severity severity, String desc, int status, int length, long responseTime) {}

    private String extractContext(String fullBody, String targetStr, int padding) {
        if (fullBody == null || targetStr == null || targetStr.isEmpty()) return "";
        int idx = fullBody.toLowerCase().indexOf(targetStr.toLowerCase());
        if (idx == -1) return "";

        int start = Math.max(0, idx - padding);
        int end = Math.min(fullBody.length(), idx + targetStr.length() + padding);
        String prefix = (start > 0) ? "..." : "";
        String suffix = (end < fullBody.length()) ? "..." : "";

        // Clean up newlines/tabs for display
        return prefix + fullBody.substring(start, end).replaceAll("[\\r\\n\\t]+", " ") + suffix;
    }

    private static final class SpecsFactory {
        static List<MutationSpec> specsFor(Object value, ModuleConfig config) {
            List<MutationSpec> specs = new ArrayList<>();

            boolean testStructural = config.getBool("pv.structural", true);
            boolean testTypeConfusion = config.getBool("pv.type_confusion", true);
            boolean testBoundary = config.getBool("pv.boundary", true);
            boolean testInjection = config.getBool("pv.injection", true);

            if (testStructural) {
                if (config.getBool("pv.null_value", true)) {
                    specs.add(new MutationSpec("NULL_VALUE", "Value replaced by null", null, false, Category.STRUCTURAL));
                }
                if (config.getBool("pv.field_removal", true)) {
                    specs.add(new MutationSpec("FIELD_REMOVED", "Field removed from body", null, true, Category.STRUCTURAL));
                }
                if (config.getBool("pv.empty_object", true)) {
                    specs.add(new MutationSpec("TYPE_EMPTY_OBJECT", "Value replaced by empty object {}", new LinkedHashMap<>(), false, Category.STRUCTURAL));
                }
                if (config.getBool("pv.empty_array", true)) {
                    specs.add(new MutationSpec("TYPE_EMPTY_ARRAY", "Value replaced by empty array []", new ArrayList<>(), false, Category.STRUCTURAL));
                }
            }

            if (value instanceof String) {
                if (testBoundary) {
                    if (config.getBool("pv.empty_string", true)) {
                        specs.add(new MutationSpec("EMPTY_STRING", "Empty string", "", false, Category.BOUNDARY));
                    }
                    if (config.getBool("pv.long_string", true)) {
                        int len = config.getInt("pv.long_string_length", 10000);
                        specs.add(new MutationSpec("STRING_LONG", "Very long string (" + len + " chars)", "A".repeat(len), false, Category.BOUNDARY));
                    }
                }
                if (testInjection) {
                    if (config.getBool("pv.sqli", true)) {
                        for (String payload : config.getString("pv.payload_sqli", "' OR '1'='1").split("\n")) {
                            specs.add(new MutationSpec("STRING_SQLI", "SQL Injection payload", payload, false, Category.INJECTION));
                        }
                    }
                    if (config.getBool("pv.sqli_time", true)) {
                        for (String payload : config.getString("pv.payload_sqli_time", "'; WAITFOR DELAY '0:0:10'--").split("\n")) {
                            specs.add(new MutationSpec("STRING_SQLI_TIME", "Time-based SQL Injection payload", payload, false, Category.INJECTION));
                        }
                    }
                    if (config.getBool("pv.xss", true)) {
                        for (String payload : config.getString("pv.payload_xss", "<script>alert(1)</script>").split("\n")) {
                            specs.add(new MutationSpec("STRING_XSS", "XSS payload", payload, false, Category.INJECTION));
                        }
                    }
                    if (config.getBool("pv.path_traversal", true)) {
                        for (String payload : config.getString("pv.payload_path_traversal", "../../../../etc/passwd").split("\n")) {
                            specs.add(new MutationSpec("STRING_PATH_TRAVERSAL", "Path Traversal payload", payload, false, Category.INJECTION));
                        }
                    }
                    if (config.getBool("pv.nosqli", true)) {
                        for (String payload : config.getString("pv.payload_nosqli", "{\"$ne\": null}").split("\n")) {
                            specs.add(new MutationSpec("STRING_NOSQLI", "NoSQL Injection payload", payload, false, Category.INJECTION));
                        }
                    }
                    if (config.getBool("pv.format_string", true)) {
                        for (String payload : config.getString("pv.payload_format_string", "%s%x%n").split("\n")) {
                            specs.add(new MutationSpec("STRING_FORMAT", "Format string payload", payload, false, Category.INJECTION));
                        }
                    }
                    if (config.getBool("pv.unicode", true)) {
                        specs.add(new MutationSpec("STRING_UNICODE", "Payload unicode / RTL override", config.getString("pv.payload_unicode", "‮test😀"), false, Category.INJECTION));
                    }
                }
                if (testTypeConfusion) {
                    if (config.getBool("pv.string_as_number", true)) {
                        specs.add(new MutationSpec("TYPE_NUMBER", "String replaced by number", 0L, false, Category.TYPE_CONFUSION));
                    }
                    if (config.getBool("pv.string_as_boolean", true)) {
                        specs.add(new MutationSpec("TYPE_BOOLEAN", "String replaced by boolean", Boolean.TRUE, false, Category.TYPE_CONFUSION));
                    }
                }
            } else if (value instanceof Long || value instanceof Integer
                    || (value instanceof RawNumber rn && rn.isInteger())) {
                if (testBoundary) {
                    if (config.getBool("pv.number_zero", true)) {
                        specs.add(new MutationSpec("NUMBER_ZERO", "Zero value", 0L, false, Category.BOUNDARY));
                    }
                    if (config.getBool("pv.number_negative", true)) {
                        specs.add(new MutationSpec("NUMBER_NEGATIVE", "Negative value", -1L, false, Category.BOUNDARY));
                    }
                    if (config.getBool("pv.number_overflow", true)) {
                        specs.add(new MutationSpec("NUMBER_OVERFLOW", "Overflow (Long.MAX_VALUE)", Long.MAX_VALUE, false, Category.BOUNDARY));
                    }
                    if (config.getBool("pv.integer_as_float", true)) {
                        specs.add(new MutationSpec("NUMBER_FLOAT", "Float where integer was expected", 1.5, false, Category.BOUNDARY));
                    }
                }
                if (testTypeConfusion) {
                    if (config.getBool("pv.number_as_string", true)) {
                        specs.add(new MutationSpec("TYPE_STRING", "Number replaced by non-numeric string", "abc", false, Category.TYPE_CONFUSION));
                    }
                    if (config.getBool("pv.number_as_numeric_string", true)) {
                        specs.add(new MutationSpec("TYPE_STRING_NUMERIC", "Number replaced by numeric string", "123", false, Category.TYPE_CONFUSION));
                    }
                }
            } else if (value instanceof Double || (value instanceof RawNumber rn && !rn.isInteger())) {
                if (testBoundary) {
                    if (config.getBool("pv.number_zero", true)) {
                        specs.add(new MutationSpec("NUMBER_ZERO", "Zero value", 0.0, false, Category.BOUNDARY));
                    }
                    if (config.getBool("pv.number_negative", true)) {
                        specs.add(new MutationSpec("NUMBER_NEGATIVE", "Negative value", -1.5, false, Category.BOUNDARY));
                    }
                }
                if (testInjection) {
                    if (config.getBool("pv.sqli", true)) {
                        specs.add(new MutationSpec("NUMBER_SQLI_MATH", "Mathematical SQL Injection payload", "1/0", false, Category.INJECTION));
                    }
                    if (config.getBool("pv.sqli_time", true)) {
                        for (String payload : config.getString("pv.payload_sqli_time", "1-(WAITFOR DELAY '0:0:10')").split("\n")) {
                            specs.add(new MutationSpec("STRING_SQLI_TIME", "Time-based SQL Injection payload (Number context)", payload, false, Category.INJECTION));
                        }
                    }
                }
                if (testTypeConfusion && config.getBool("pv.number_as_string", true)) {
                    specs.add(new MutationSpec("TYPE_STRING", "Number replaced by string", "abc", false, Category.TYPE_CONFUSION));
                }
            } else if (value instanceof Boolean b) {
                if (testBoundary && config.getBool("pv.boolean_flip", true)) {
                    specs.add(new MutationSpec("BOOLEAN_FLIP", "Boolean flipped", !b, false, Category.BOUNDARY));
                }
                if (testTypeConfusion) {
                    if (config.getBool("pv.boolean_as_string", true)) {
                        specs.add(new MutationSpec("TYPE_STRING", "Boolean replaced by string", "true", false, Category.TYPE_CONFUSION));
                    }
                    if (config.getBool("pv.boolean_as_number", true)) {
                        specs.add(new MutationSpec("TYPE_NUMBER", "Boolean replaced by number", 1L, false, Category.TYPE_CONFUSION));
                    }
                }
            }
            return specs;
        }
    }

    private static final class PathRules {
        private final List<String> includes;
        private final List<String> excludes;
        private final List<String> exceptions;

        PathRules(List<String> includes, List<String> excludes, List<String> exceptions) {
            this.includes = includes;
            this.excludes = excludes;
            this.exceptions = exceptions;
        }

        boolean isIncluded(String path) {
            if (includes == null || includes.isEmpty()) return true;
            return matchesAny(path, includes);
        }

        boolean isExcluded(String path) {
            return excludes != null && matchesAny(path, excludes);
        }

        boolean isException(String path, MutationSpec spec) {
            if (exceptions == null || exceptions.isEmpty()) return false;
            for (String rawRule : exceptions) {
                if (rawRule == null || rawRule.isBlank()) continue;
                int separatorIndex = rawRule.lastIndexOf("::");
                if (separatorIndex <= 0 || separatorIndex >= rawRule.length() - 2) continue;

                String pathPattern = rawRule.substring(0, separatorIndex).trim();
                String exceptionRule = rawRule.substring(separatorIndex + 2).trim();

                if (!matches(path, pathPattern)) continue;

                if (exceptionRule.equalsIgnoreCase("ALL")) return true;
                if (exceptionRule.regionMatches(true, 0, "CATEGORY:", 0, 9)) {
                    String categoryName = exceptionRule.substring(9).trim();
                    if (spec.category().name().equalsIgnoreCase(categoryName)) return true;
                    continue;
                }
                if (spec.type().equalsIgnoreCase(exceptionRule)) return true;
            }
            return false;
        }

        private boolean matchesAny(String path, List<String> patterns) {
            for (String pattern : patterns) {
                if (pattern != null && !pattern.isBlank() && matches(path, pattern.trim())) return true;
            }
            return false;
        }

        private boolean matches(String path, String pattern) {
            if (path == null || pattern == null || pattern.isBlank()) return false;
            String normalizedPattern = pattern.trim();
            if (normalizedPattern.endsWith(".**")) {
                String basePattern = normalizedPattern.substring(0, normalizedPattern.length() - 3);
                return matches(path, basePattern) || matches(path, basePattern + ".*") || pathMatchesDescendantPattern(path, basePattern);
            }
            return path.matches(buildRegex(normalizedPattern));
        }

        private boolean pathMatchesDescendantPattern(String path, String basePattern) {
            String baseRegex = buildRegex(basePattern);
            if (baseRegex.endsWith("$")) {
                baseRegex = baseRegex.substring(0, baseRegex.length() - 1);
            }
            return path.matches(baseRegex + "(?:\\..+|\\[\\d+\\].*)$");
        }

        private String buildRegex(String pattern) {
            StringBuilder regex = new StringBuilder("^");
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
                char current = pattern.charAt(index);
                if (current == '*') {
                    regex.append("[^.\\[]+");
                    index++;
                    continue;
                }
                if ("\\.^$|?+(){}[]".indexOf(current) >= 0) {
                    regex.append("\\");
                }
                regex.append(current);
                index++;
            }
            regex.append("$");
            return regex.toString();
        }
    }
}
