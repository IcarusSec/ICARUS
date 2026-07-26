package icarus.modules;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import icarus.core.Category;
import icarus.core.Finding;
import icarus.core.IcarusModule;
import icarus.core.JsonParser;
import icarus.core.ModuleConfig;
import icarus.core.Severity;

import java.util.*;

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
    public List<Finding> run(HttpRequestResponse requestResponse, ModuleConfig config) {
        HttpRequest request = requestResponse.request();
        String contentType = request.headerValue("Content-Type");
        String originalBody = request.bodyToString();

        boolean looksLikeJson = (contentType != null && contentType.toLowerCase().contains("json"))
                || (originalBody != null && (originalBody.trim().startsWith("{") || originalBody.trim().startsWith("[")));

        if (originalBody == null || originalBody.isBlank() || !looksLikeJson) {
            return List.of();
        }

        Object originalRoot = JsonParser.parse(originalBody);
        List<List<Object>> allPaths = Paths.collect(originalRoot);

        PathRules pathRules = new PathRules(
                config.getStringList("pv.include_paths"),
                config.getStringList("pv.exclude_paths"),
                config.getStringList("pv.path_exceptions")
        );

        List<List<Object>> eligiblePaths = new ArrayList<>();
        for (List<Object> path : allPaths) {
            String pathString = Paths.pathToString(path);
            if (!pathRules.isIncluded(pathString)) continue;
            if (pathRules.isExcluded(pathString)) continue;
            eligiblePaths.add(path);
        }

        int maxMutations = config.getInt("pv.max_mutations", 60);
        List<Mutation> mutations = new ArrayList<>();
        
        outer:
        for (List<Object> path : eligiblePaths) {
            String pathString = Paths.pathToString(path);
            Object leafValue = Paths.getAt(JsonParser.parse(originalBody), path);
            
            for (MutationSpec spec : SpecsFactory.specsFor(leafValue, config)) {
                if (pathRules.isException(pathString, spec)) continue;
                if (mutations.size() >= maxMutations) break outer;
                
                Object clonedRoot = JsonParser.parse(originalBody);
                boolean applied = Paths.applyAt(clonedRoot, path, spec);
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
        
        for (int i = 0; i < mutatedRequests.size(); i++) {
            long startTime = System.currentTimeMillis();
            try {
                HttpRequestResponse result = api.http().sendRequest(mutatedRequests.get(i));
                responses.add(result);
            } catch (Exception e) {
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
            } catch (Exception ignored) {}
            baselineTime = System.currentTimeMillis() - st;
        }

        List<Finding> findings = new ArrayList<>();

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

            boolean timeDelayHit = mutation.type().equals("STRING_SQLI_TIME") && responseTime >= timeDelayMs;

            boolean xssReflectionHit = false;
            if (checkXssReflection && mutation.type().equals("STRING_XSS") && mutation.value() instanceof String payload) {
                xssReflectionHit = bodyStr.contains(payload);
            }

            boolean accepted = (status >= findingStatusMin && status <= findingStatusMax) || timeDelayHit || xssReflectionHit;

            if (accepted && filterExactMatch && length == baselineLength && !timeDelayHit && !xssReflectionHit) {
                accepted = false;
            }

            boolean behavioralHit = false;
            String behavioralReason = "";
            if (behavioralAnalysis && baselineLength != -1 && !accepted) {
                double diffRatio = baselineLength == 0 ? 0 : Math.abs(length - baselineLength) / (double) baselineLength;
                if (diffRatio > 0.20) {
                    behavioralHit = true;
                    behavioralReason = "Size anomaly (" + length + " vs baseline " + baselineLength + ")";
                } else if (baselineTime > 0 && responseTime > baselineTime * 5 && responseTime > 3000) {
                    behavioralHit = true;
                    behavioralReason = "Time anomaly (" + responseTime + "ms vs baseline " + baselineTime + "ms)";
                } else {
                    String lowerBody = bodyStr.toLowerCase();
                    if ((lowerBody.contains("syntax error") || lowerBody.contains("sql syntax")
                            || lowerBody.contains("ora-") || lowerBody.contains("warning: mysql_")) &&
                        (requireBaseline ? !baselineBodyLower.contains("syntax error") : true)) {
                        behavioralHit = true;
                        behavioralReason = "Backend error anomaly";
                    }
                }
                if (behavioralHit) {
                    accepted = true;
                }
            }

            if (accepted) {
                Severity severity = Severity.HIGH;
                if (!behavioralHit && !timeDelayHit && !xssReflectionHit) {
                    if (mutation.category() == Category.STRUCTURAL) severity = Severity.MEDIUM;
                    else if (mutation.category() == Category.BOUNDARY) severity = Severity.MEDIUM;
                    else if (mutation.category() == Category.TYPE_CONFUSION) severity = Severity.MEDIUM;
                }

                String findingDesc = mutation.description() + " | HTTP=" + status + " | size=" + length;
                if (timeDelayHit) findingDesc += " | time=" + responseTime + "ms";
                else if (xssReflectionHit) findingDesc += " | XSS payload reflected!";
                else if (behavioralHit) findingDesc += " | Behavioral: " + behavioralReason;
                else findingDesc += " | payload accepted";
                
                findings.add(Finding.builder(name(), mutation.type())
                        .description(findingDesc)
                        .severity(severity)
                        .category(mutation.category())
                        .path(mutation.path())
                        .evidence(mutatedResult)
                        .meta("status", String.valueOf(status))
                        .meta("length", String.valueOf(length))
                        .meta("responseTime", String.valueOf(responseTime))
                        .build());
            }
        }
        
        return findings;
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
            } else if (value instanceof Long || value instanceof Integer) {
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
            } else if (value instanceof Double) {
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

    private static final class Paths {
        static List<List<Object>> collect(Object root) {
            List<List<Object>> out = new ArrayList<>();
            walk(root, new ArrayList<>(), out);
            return out;
        }

        private static void walk(Object node, List<Object> current, List<List<Object>> out) {
            if (node instanceof Map<?, ?> map) {
                for (Object key : map.keySet()) {
                    List<Object> childPath = new ArrayList<>(current);
                    childPath.add(key);
                    out.add(childPath);
                    Object value = map.get(key);
                    if (value instanceof Map || value instanceof List) walk(value, childPath, out);
                }
            } else if (node instanceof List<?> list) {
                for (int i = 0; i < list.size(); i++) {
                    List<Object> childPath = new ArrayList<>(current);
                    childPath.add(i);
                    out.add(childPath);
                    Object value = list.get(i);
                    if (value instanceof Map || value instanceof List) walk(value, childPath, out);
                }
            }
        }

        static Object getAt(Object root, List<Object> path) {
            Object current = root;
            for (Object key : path) {
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
                Object key = path.get(i);
                if (parent instanceof Map<?, ?> m && key instanceof String s) parent = m.get(s);
                else if (parent instanceof List<?> l && key instanceof Integer idx) parent = l.get(idx);
                else return false;
            }
            Object lastKey = path.get(path.size() - 1);
            if (spec.remove()) {
                if (parent instanceof Map<?, ?> m && lastKey instanceof String s) {
                    ((Map<Object, Object>) m).remove(s);
                    return true;
                }
                return false; 
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
            StringBuilder sb = new StringBuilder("$");
            for (Object p : path) {
                if (p instanceof String s) sb.append(".").append(s);
                else sb.append("[").append(p).append("]");
            }
            return sb.toString();
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
