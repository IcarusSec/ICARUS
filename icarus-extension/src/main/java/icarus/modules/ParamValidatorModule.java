package icarus.modules;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.collaborator.CollaboratorClient;
import burp.api.montoya.collaborator.CollaboratorPayload;
import burp.api.montoya.collaborator.Interaction;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.params.HttpParameter;
import burp.api.montoya.http.message.params.HttpParameterType;
import burp.api.montoya.http.message.params.ParsedHttpParameter;
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
import icarus.core.I18n;

import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

public final class ParamValidatorModule implements IcarusModule {

    private final MontoyaApi api;

    public ParamValidatorModule(MontoyaApi api) {
        this.api = api;
    }

    @Override
    public String name() {
        return "ParamValidator";
    }

    @Override
    public boolean sendsActivePayloads() {
        return true;
    }

    record MutationSpec(String type, String description, Object value, boolean remove, Category category, icarus.modules.ast.mutators.AstMutationResult astResult) {
        public MutationSpec(String type, String description, Object value, boolean remove, Category category) {
            this(type, description, value, remove, category, null);
        }
    }
    record Mutation(String path, String type, String description, Category category, HttpRequest request, Object value, boolean remove) {}

    /** One baseline probe: send + measure. status/length == -1 and empty body on a null response. */
    record BaselineSample(int status, int length, long millis, String bodyLower) {}

    private BaselineSample captureBaseline(HttpRequest request) {
        long st = System.currentTimeMillis();
        try {
            HttpRequestResponse rr = api.http().sendRequest(request);
            if (rr == null || rr.response() == null) return new BaselineSample(-1, -1, 0, "");
            HttpResponse resp = rr.response();
            return new BaselineSample(resp.statusCode(), resp.body().length(),
                    System.currentTimeMillis() - st, resp.bodyToString().toLowerCase());
        } catch (Exception e) {
            api.logging().logToError(I18n.t("module.pv.err.baseline_failed", e));
            return new BaselineSample(-1, -1, 0, "");
        }
    }

    /** Backfill any missing/blank payload key with its seed so existing installs stop showing blank tabs. */
    public static void ensurePayloadDefaults(ModuleConfig config) {
        String[][] defs = {
                {"pv.payload_sqli", PayloadRepository.SQLI_DEFAULT},
                {"pv.payload_sqli_time", PayloadRepository.SQLI_TIME_DEFAULT},
                {"pv.payload_sqli_time_number", PayloadRepository.SQLI_TIME_NUMBER_DEFAULT},
                {"pv.payload_xss", PayloadRepository.XSS_DEFAULT},
                {"pv.payload_path_traversal", PayloadRepository.PATH_TRAVERSAL_DEFAULT},
                {"pv.payload_nosqli", PayloadRepository.NOSQLI_DEFAULT},
                {"pv.payload_format_string", PayloadRepository.FORMAT_STRING_DEFAULT},
                {"pv.payload_unicode", PayloadRepository.UNICODE_DEFAULT},
                {"pv.payload_cmdi", PayloadRepository.CMDI_DEFAULT},
                {"pv.payload_ssti", PayloadRepository.SSTI_DEFAULT},
                {"pv.payload_ssrf_heuristic", PayloadRepository.SSRF_HEURISTIC_DEFAULT},
        };
        for (String[] d : defs) {
            String v = config.getString(d[0], "");
            if (v == null || v.isBlank()) config.set(d[0], d[1]);
        }
        // Pre-depth builds persisted pv.max_mutations=60 as the hard cap. With scan depth
        // that value now overrides the depth budget, so DEEP would stay silently capped at
        // 60. Migrate that exact legacy default to 0 (= auto from depth) once.
        if (!config.getBool("pv.depth_migrated", false)) {
            if (config.getInt("pv.max_mutations", 0) == 60) config.set("pv.max_mutations", 0);
            config.set("pv.depth_migrated", true);
        }
    }

    /** Split a textarea list on any line break, trim, drop blanks. */
    static List<String> splitPayloads(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        return Arrays.stream(raw.split("\\R"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    static String normalizeList(String raw) {
        return String.join("\n", splitPayloads(raw));
    }

    static boolean isStockList(String stored, String seed) {
        return normalizeList(stored).equals(normalizeList(seed));
    }

    /** DEEP-only built-in evasion extras for a stock list, keyed by payload config key. */
    static List<String> deepExtras(String key) {
        switch (key == null ? "" : key) {
            case "pv.payload_sqli":           return PayloadRepository.SQLI_EVASION;
            case "pv.payload_xss":            return PayloadRepository.XSS_EVASION;
            case "pv.payload_path_traversal": return PayloadRepository.PATH_TRAVERSAL_EVASION;
            case "pv.payload_cmdi":           return PayloadRepository.CMDI_EVASION;
            case "pv.payload_ssti":           return PayloadRepository.SSTI_EVASION;
            case "pv.payload_nosqli":         return PayloadRepository.NOSQLI_EVASION;
            case "pv.payload_ssrf_heuristic": return PayloadRepository.SSRF_EVASION;
            default:                          return List.of();
        }
    }

    /**
     * Resolve a payload list: a hand-authored (non-stock) list is used verbatim; a stock list is
     * depth-shaped — LIGHT = first payload only, MEDIUM = full seed, DEEP = full seed + deepExtras.
     */
    static List<String> payloadsFor(String key, String seed, String depth, ModuleConfig config) {
        String stored = config.getString(key, seed);
        if (stored == null || stored.isBlank()) stored = seed;
        if (!isStockList(stored, seed)) return splitPayloads(stored);

        List<String> seedList = splitPayloads(seed);
        switch (depth == null ? "MEDIUM" : depth) {
            case "LIGHT":
                return seedList.isEmpty() ? seedList : List.of(seedList.get(0));
            case "DEEP": {
                List<String> extras = deepExtras(key);
                if (extras.isEmpty()) return seedList;
                List<String> combined = new ArrayList<>(seedList);
                for (String e : extras) if (!combined.contains(e)) combined.add(e);
                return combined;
            }
            default:
                return seedList;
        }
    }

    /**
     * Cut {@code specs} to {@code budget}, round-robin across MutationSpec.type() buckets so a long
     * injection list can't starve later IDOR/SSRF specs. Intra-type order preserved.
     */
    private static List<MutationSpec> truncateFairlyByType(List<MutationSpec> specs, int budget) {
        LinkedHashMap<String, ArrayDeque<MutationSpec>> byType = new LinkedHashMap<>();
        for (MutationSpec s : specs) byType.computeIfAbsent(s.type(), k -> new ArrayDeque<>()).add(s);
        List<MutationSpec> out = new ArrayList<>(budget);
        while (out.size() < budget) {
            boolean progressed = false;
            for (ArrayDeque<MutationSpec> q : byType.values()) {
                if (q.isEmpty()) continue;
                out.add(q.poll());
                progressed = true;
                if (out.size() >= budget) break;
            }
            if (!progressed) break;
        }
        return out;
    }

    @Override
    public List<Finding> run(HttpRequestResponse requestResponse, ModuleConfig config, Consumer<String> logger) {
        ensurePayloadDefaults(config);
        HttpRequest request = requestResponse.request();
        String contentType = request.headerValue("Content-Type");
        String originalBody = request.bodyToString();

        boolean looksLikeJson = (contentType != null && contentType.toLowerCase().contains("json"))
                || (originalBody != null && (originalBody.trim().startsWith("{") || originalBody.trim().startsWith("[")));
        boolean hasJsonBody = originalBody != null && !originalBody.isBlank() && looksLikeJson;

        // URL query params + form-urlencoded body params (Montoya only populates
        // HttpParameterType.BODY for form-encoded bodies, so this list is naturally
        // exclusive with hasJsonBody; the flat-param loop below is still guarded on !hasJsonBody).
        List<ParsedHttpParameter> flatParams = request.parameters().stream()
                .filter(p -> p.type() == HttpParameterType.URL || p.type() == HttpParameterType.BODY)
                .toList();

        if (!hasJsonBody && flatParams.isEmpty()) {
            return List.of();
        }

        Object originalRoot = hasJsonBody ? JsonParser.parse(originalBody) : null;
        List<List<Object>> allPaths = hasJsonBody ? JsonPaths.collect(originalRoot) : List.of();
        
        icarus.modules.ast.OffensiveAstRoot astRoot = null;
        if (hasJsonBody) {
            try {
                astRoot = icarus.modules.ast.OffensiveJsonParser.parse(originalBody.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            } catch (Exception e) {
                logger.accept("Failed to parse JSON into AST: " + e.getMessage());
            }
        }
        Map<String, List<icarus.modules.ast.mutators.AstMutationResult>> astMutationsByPath = new HashMap<>();
        if (astRoot != null) {
            for (icarus.modules.ast.mutators.AstMutationResult res : icarus.modules.ast.AstMutationGenerator.generateMutations(astRoot)) {
                astMutationsByPath.computeIfAbsent(res.path, k -> new ArrayList<>()).add(res);
            }
        }

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

        int jsonCount = eligiblePaths.size();
        int urlOnlyCount = hasJsonBody ? 0
                : (int) flatParams.stream().filter(p -> p.type() == HttpParameterType.URL).count();
        int formOnlyCount = hasJsonBody ? 0
                : (int) flatParams.stream().filter(p -> p.type() == HttpParameterType.BODY).count();
        logger.accept(I18n.t("module.pv.log.detected_inputs",
                jsonCount + urlOnlyCount + formOnlyCount, jsonCount, urlOnlyCount, formOnlyCount));

        String depth = config.getString("pv.depth", "MEDIUM"); // LIGHT | MEDIUM | DEEP
        int configuredMax = Math.max(0, config.getInt("pv.max_mutations", 0));
        int maxMutations = configuredMax > 0 ? configuredMax
                : switch (depth) {
                      case "LIGHT" -> 20;
                      case "DEEP"  -> 200;
                      default      -> 60;   // MEDIUM + any unknown value
                  };
        List<Mutation> mutations = new ArrayList<>();

        // Round-robin across fields (one spec per field per pass) instead of exhausting one
        // field's full spec list before moving to the next. A single string field can generate
        // ~25 specs (multi-line injection payloads); depth-first order meant maxMutations was
        // often spent entirely on the first field or two, so later fields — often where numeric
        // params live — silently got zero boundary specs (e.g. NUMBER_NEGATIVE) generated at all.
        // Each field is either a JSON body path (fieldPaths set, fieldFlatParam null) or a flat
        // URL/form param (fieldFlatParam set, fieldPaths null) — mutually exclusive per index.
        // Collaborator client is shared across every field so each gets its own uniquely
        // correlatable payload from the same session; created once and reused, never per-field.
        // Burp Collaborator is unavailable in some environments (disabled, air-gapped network)
        // — createClient() throwing just means every SSRF spec below falls back to the
        // response-signature heuristic instead of out-of-band callbacks.
        CollaboratorClient collaboratorClient = null;
        if (config.getBool("pv.ssrf", true)) {
            try {
                collaboratorClient = api.collaborator().createClient();
            } catch (Exception e) {
                logger.accept(I18n.t("module.pv.log.collab_unavailable", e.getMessage()));
            }
        }
        Map<String, CollaboratorPayload> ssrfPayloadsByField = new LinkedHashMap<>();

        List<String> fieldPathStrings = new ArrayList<>();
        List<List<Object>> fieldPaths = new ArrayList<>();
        List<ParsedHttpParameter> fieldFlatParam = new ArrayList<>();
        List<List<MutationSpec>> fieldSpecs = new ArrayList<>();
        for (List<Object> path : eligiblePaths) {
            String pathString = JsonPaths.pathToString(path);
            Object leafValue = JsonPaths.getAt(originalRoot, path);

            List<MutationSpec> specs = new ArrayList<>();
            for (MutationSpec spec : SpecsFactory.specsFor(leafValue, config, depth)) {
                if (pathRules.isException(pathString, spec)) continue;
                specs.add(spec);
            }
            for (MutationSpec spec : contextualSpecs(pathString, leafValue, config, collaboratorClient, ssrfPayloadsByField, depth)) {
                if (pathRules.isException(pathString, spec)) continue;
                specs.add(spec);
            }
            List<icarus.modules.ast.mutators.AstMutationResult> astSpecs = astMutationsByPath.get(pathString);
            if (astSpecs != null) {
                for (icarus.modules.ast.mutators.AstMutationResult astRes : astSpecs) {
                    MutationSpec spec = new MutationSpec(astRes.type, astRes.description, astRes.value, false, astRes.category, astRes);
                    if (pathRules.isException(pathString, spec)) continue;
                    specs.add(spec);
                }
            }
            if (!specs.isEmpty()) {
                fieldPathStrings.add(pathString);
                fieldPaths.add(path);
                fieldFlatParam.add(null);
                fieldSpecs.add(specs);
            }
        }
        // numeric-looking flat values are typed as String, matching URL-param behaviour
        if (!hasJsonBody) {
            for (ParsedHttpParameter param : flatParams) {
                String pathString = (param.type() == HttpParameterType.BODY ? "body:" : "url:") + param.name();
                if (!pathRules.isIncluded(pathString)) continue;
                if (pathRules.isExcluded(pathString)) continue;

                List<MutationSpec> specs = new ArrayList<>();
                for (MutationSpec spec : SpecsFactory.specsFor(param.value(), config, depth)) {
                    if (pathRules.isException(pathString, spec)) continue;
                    specs.add(spec);
                }
                for (MutationSpec spec : contextualSpecs(pathString, param.value(), config, collaboratorClient, ssrfPayloadsByField, depth)) {
                    if (pathRules.isException(pathString, spec)) continue;
                    specs.add(spec);
                }
                if (!specs.isEmpty()) {
                    fieldPathStrings.add(pathString);
                    fieldPaths.add(null);
                    fieldFlatParam.add(param);
                    fieldSpecs.add(specs);
                }
            }
        }

        // A single field whose spec list alone exceeds the resolved budget gets cut type-fairly
        // here (preserving intra-type order) so a long injection list can't crowd out that same
        // field's IDOR/SSRF specs. The field-level round-robin below still protects numeric params.
        for (int f = 0; f < fieldSpecs.size(); f++) {
            List<MutationSpec> specs = fieldSpecs.get(f);
            if (specs.size() > maxMutations) {
                fieldSpecs.set(f, truncateFairlyByType(specs, maxMutations));
            }
        }

        for (int round = 0; mutations.size() < maxMutations; round++) {
            boolean anyFieldHadSpecAtThisRound = false;
            for (int f = 0; f < fieldSpecs.size(); f++) {
                if (mutations.size() >= maxMutations) break;
                List<MutationSpec> specs = fieldSpecs.get(f);
                if (round >= specs.size()) continue;
                anyFieldHadSpecAtThisRound = true;

                MutationSpec spec = specs.get(round);
                ParsedHttpParameter fp = fieldFlatParam.get(f);
                if (fp != null) {
                    // §1.5: Montoya fixes Content-Length on withUpdatedParameters; whether it
                    // percent-encodes the value is unverified — payloads with & = + # % need a
                    // wire-capture check against testdata/echo_server.py before trusting this.
                    boolean isBody = fp.type() == HttpParameterType.BODY;
                    String v = spec.value() != null ? spec.value().toString() : "";
                    HttpParameter removeTarget = isBody
                            ? HttpParameter.bodyParameter(fp.name(), fp.value())
                            : HttpParameter.urlParameter(fp.name(), fp.value());
                    HttpParameter updateTarget = isBody
                            ? HttpParameter.bodyParameter(fp.name(), v)
                            : HttpParameter.urlParameter(fp.name(), v);
                    HttpRequest mutatedRequest = spec.remove()
                            ? request.withRemovedParameters(removeTarget)
                            : request.withUpdatedParameters(updateTarget);
                    mutations.add(new Mutation(
                            fieldPathStrings.get(f),
                            spec.type(),
                            spec.description(),
                            spec.category(),
                            mutatedRequest,
                            spec.value(),
                            spec.remove()
                    ));
                } else {
                    if (spec.astResult() != null) {
                        try {
                            byte[] mutatedBody = icarus.modules.ast.AstSerializer.serialize(spec.astResult().root).payload;
                            mutations.add(new Mutation(
                                    fieldPathStrings.get(f),
                                    spec.type(),
                                    spec.description(),
                                    spec.category(),
                                    request.withBody(burp.api.montoya.core.ByteArray.byteArray(mutatedBody)),
                                    spec.value(),
                                    spec.remove()
                            ));
                        } catch (Exception e) {
                            api.logging().logToError("Failed to serialize AST: " + e.getMessage());
                        }
                    } else {
                        Object clonedRoot = JsonParser.parse(originalBody);
                        boolean applied = JsonPaths.applyAt(clonedRoot, fieldPaths.get(f), spec.value(), spec.remove());
                        if (applied) {
                            mutations.add(new Mutation(
                                    fieldPathStrings.get(f),
                                    spec.type(),
                                    spec.description(),
                                    spec.category(),
                                    request.withBody(JsonParser.write(clonedRoot)),
                                    spec.value(),
                                    spec.remove()
                            ));
                        }
                    }
                }
            }
            if (!anyFieldHadSpecAtThisRound) break; // every field's spec list is exhausted
        }

        if (mutations.isEmpty()) {
            return List.of();
        }

        boolean requireBaseline = config.getBool("pv.require_baseline", true);
        int baselineStatus = -1;
        int baselineLength = -1;
        long baselineTime = 0;
        String baselineBodyLower = "";
        boolean baselineStable = false;

        if (requireBaseline) {
            BaselineSample a = captureBaseline(request);
            if (a.status() == -1) return List.of();   // PRESERVE main's null-response early return
            BaselineSample b = captureBaseline(request);

            baselineStatus = a.status();
            baselineLength = a.length();
            // TIMING: min of the two samples, NOT a.millis() (A eats TLS/cache-miss cost).
            // B's -1 sentinel on a failed second probe must NOT leak into the min.
            baselineTime = (b.status() != -1) ? Math.min(a.millis(), b.millis()) : a.millis();
            // BODY: sample A, empty-fallback to B — only used for .contains() checks.
            baselineBodyLower = !a.bodyLower().isEmpty() ? a.bodyLower() : b.bodyLower();

            baselineStable = a.status() == b.status()
                    && Math.abs(a.length() - b.length()) <= Math.max(32, a.length() / 50);
            if (!baselineStable) {
                logger.accept(I18n.t("module.pv.log.baseline_unstable",
                        a.status(), a.length(), b.status(), b.length()));
            }

            boolean baselineNon2xx = baselineStatus < 200 || baselineStatus > 299;
            if (baselineNon2xx && !config.getBool("pv.scan_non_2xx_baseline", true)) {
                logger.accept(I18n.t("module.pv.log.baseline_non2xx_abort", baselineStatus));
                return List.of();
            }
            if (baselineNon2xx) {
                logger.accept(I18n.t("module.pv.log.baseline_non2xx", baselineStatus));
            }
        } else {
            // Passive scan: one sample, no pair — treat the baseline as untrustworthy so
            // §2.3's relaxation and §3's transition detection both stay off.
            HttpResponse r = requestResponse.response();
            if (r != null) {
                baselineStatus = r.statusCode();
                baselineLength = r.body().length();
                baselineBodyLower = r.bodyToString().toLowerCase();
            }
            baselineStable = false;
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
            mutatedRequests.add(m.request());
        }

        long[] requestTimes = new long[mutatedRequests.size()];
        List<HttpRequestResponse> responses = new ArrayList<>();

        boolean promptedThrottle = false;
        int delayMs = 0;

        int blockStreak = 0;

        for (int i = 0; i < mutatedRequests.size(); i++) {
            icarus.ScanRunner.waitIfPaused();
            if (Thread.currentThread().isInterrupted() || icarus.ScanRunner.isStopRequested() || icarus.ScanRunner.isSkipRequested()) {
                logger.accept(I18n.t("module.pv.log.stopped_by_user", (mutatedRequests.size() - i)));
                break;
            }
            Mutation m = mutations.get(i);
            logger.accept(I18n.t("module.pv.log.testing_mutation", shortPath(m.path()), m.description().toLowerCase()));

            if (delayMs > 0) {
                try { Thread.sleep(delayMs); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }

            long startTime = System.currentTimeMillis();
            try {
                HttpRequestResponse result = api.http().sendRequest(mutatedRequests.get(i));
                responses.add(result);
                if (result != null && result.response() != null) {
                    int st = result.response().statusCode();
                    if (st == 429) {
                        // Unlike 401/403 (could just be an app-level auth failure), 429 is an
                        // unambiguous rate-limit signal — throttle automatically, no prompt.
                        blockStreak++;
                        long retryAfterMs = parseRetryAfterMs(result.response().headerValue("Retry-After"));
                        long wanted = retryAfterMs > 0 ? retryAfterMs : 2000;
                        if (wanted > delayMs) {
                            delayMs = (int) Math.min(wanted, 30_000);
                            logger.accept(I18n.t("module.pv.log.throttling_429", delayMs));
                        }
                    } else if (st == 401 || st == 403) {
                        blockStreak++;
                        logger.accept(I18n.t("module.pv.log.tool_returning", st));
                        if (!promptedThrottle) {
                            promptedThrottle = true;
                            // Blocking dialog must run on the EDT (CLAUDE.md) — same invokeAndWait
                            // pattern ScanRunner already uses for its Akamai prompt.
                            int[] choiceHolder = { javax.swing.JOptionPane.NO_OPTION };
                            try {
                                javax.swing.SwingUtilities.invokeAndWait(() -> choiceHolder[0] = javax.swing.JOptionPane.showConfirmDialog(api.userInterface().swingUtils().suiteFrame(),
                                    I18n.t("module.pv.ui.waf_throttle_msg", st),
                                    I18n.t("module.pv.ui.waf_throttle_title"), javax.swing.JOptionPane.YES_NO_OPTION, javax.swing.JOptionPane.WARNING_MESSAGE));
                            } catch (InterruptedException ex) {
                                Thread.currentThread().interrupt();
                            } catch (java.lang.reflect.InvocationTargetException ex) {
                                api.logging().logToError(I18n.t("module.pv.err.waf_dialog_failed", ex.getCause()));
                            }
                            if (choiceHolder[0] == javax.swing.JOptionPane.YES_OPTION) {
                                delayMs = 2000;
                            }
                        }
                    } else {
                        blockStreak = 0;
                    }

                    // A flat delay that never adapts leaves the scan stuck failing indefinitely
                    // once a CDN/WAF keeps blocking through it. Escalate every 3 consecutive
                    // blocked requests, capped so a persistent block can't stall the scan forever.
                    if (delayMs > 0 && blockStreak > 0 && blockStreak % 3 == 0) {
                        int escalated = (int) Math.min(delayMs * 2L, 30_000);
                        if (escalated > delayMs) {
                            delayMs = escalated;
                            logger.accept(I18n.t("module.pv.log.still_blocked", blockStreak, delayMs));
                        }
                    }
                }
            } catch (Exception e) {
                api.logging().logToError(I18n.t("module.pv.err.mutation_failed", e));
                logger.accept(I18n.t("module.pv.log.request_failed", e.getMessage()));
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

        List<Finding> findings = new ArrayList<>();
        Map<String, List<MutationResult>> groupedByPath = new LinkedHashMap<>();
        // §3.2: one status-transition finding per (path, transitionClass); session_lost logged once.
        Set<String> statusTransitionsSeen = new HashSet<>();
        boolean sessionLostLogged = false;

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

            // Robust rejection of WAF/Auth blocks — checked BEFORE injection
            // detection so a WAF block page can't be mistaken for a confirmed injection hit.
            boolean isWafBlock = status == 401 || status == 403 || status == 406;
            if (!isWafBlock && status == 400) {
                // Check common WAF headers
                String serverHeader = mutatedResponse.headerValue("Server");
                if (serverHeader != null && (serverHeader.toLowerCase().contains("cloudflare") || serverHeader.toLowerCase().contains("akamai"))) {
                    isWafBlock = true;
                }
            }
            if (!isWafBlock && icarus.modules.WafFingerprint.looksBlocked(mutatedResponse)) isWafBlock = true;
            if (isWafBlock) {
                continue;
            }

            // Any other 4xx is an expected application-level rejection (400/404/422 etc.),
            // not a WAF block — only the generic size/time drift heuristic below is gated on
            // it. Time-based SQLi, XSS reflection, and the CWE-209 verbose-error check still
            // run on 4xx responses since apps commonly leak SQL/stack errors on validation
            // failure pages coded 400/422, not just 500.
            // A 4xx mutation is "expected" (excluded from drift / verbose-error heuristics)
            // ONLY when the baseline was 2xx. When the baseline was ITSELF a stable 4xx and the
            // status_transition flag is on, both responses come from the same error handler, so
            // drift between them is real signal — analyse it.
            boolean baselineIs4xx = baselineStatus >= 400 && baselineStatus <= 499;
            boolean relax4xxDrift = config.getBool("pv.status_transition_detection", false);
            boolean isExpectedRejection = (status >= 400 && status <= 499)
                    && !(baselineIs4xx && baselineStable && relax4xxDrift);

            // ── Injection Context Extraction Engine ──
            String extractedContext = "";
            boolean isInjectionFinding = false;
            String injectionDesc = "";
            Severity injectionSeverity = Severity.HIGH;

            // Calculate statistical variance for Time-based SQLi
            boolean timeDelayHit = false;
            if (mutation.type().equals("STRING_SQLI_TIME")) {
                // Minimum threshold check
                if (responseTime >= timeDelayMs) {
                    // Statistical validation against baseline
                    double diff = Math.abs(responseTime - baselineTime);
                    // If the baseline was already slow, we need standard deviation (simplified here)
                    // For now, require the payload response to be at least 2.5x slower than the baseline
                    // AND at least the timeDelayMs difference
                    if (responseTime > (baselineTime * 2.5) && diff >= (timeDelayMs * 0.8)) {
                        timeDelayHit = true;
                    }
                }
            }
            if (timeDelayHit) {
                isInjectionFinding = true;
                injectionDesc = I18n.t("module.pv.finding.desc.sqli_time", baselineTime, responseTime);
            }

            boolean xssReflectionHit = false;
            boolean xssUncertain = false;
            if (checkXssReflection && mutation.type().equals("STRING_XSS") && mutation.value() instanceof String payload) {
                if (bodyStr.contains(payload)) {
                    // It reflected exactly. Is it in an executable context or just a JSON response?
                    String resContentType = mutatedResponse.headerValue("Content-Type");
                    if (resContentType != null && (resContentType.toLowerCase().contains("application/json") || resContentType.toLowerCase().contains("text/plain"))) {
                        // Reflected inside JSON or text - usually not exploitable directly unless the client misparses it.
                        xssUncertain = true;
                    } else {
                        // Use JSoup to validate if the payload broke out of the DOM
                        org.jsoup.nodes.Document doc = org.jsoup.Jsoup.parse(bodyStr);
                        // A true XSS payload like <svg/onmouseover="confirm(1)"/class="x"> 
                        // should result in an SVG element with those attributes in the DOM.
                        // If it's escaped, Jsoup parses it as text nodes, not elements.
                        boolean foundExecutableDom = !doc.getElementsByTag("svg").isEmpty() || 
                                                     !doc.getElementsByTag("details").isEmpty() ||
                                                     !doc.getElementsByAttribute("onmouseover").isEmpty() ||
                                                     !doc.getElementsByAttribute("ontoggle").isEmpty() ||
                                                     !doc.getElementsByAttribute("onload").isEmpty();
                        
                        if (foundExecutableDom) {
                            xssReflectionHit = true;
                        } else {
                            // The exact string is there, but JSoup didn't parse our injected tags/attributes.
                            // This might be a false positive (escaped context) or inside a <script> block which JSoup treats as data.
                            // We mark it as uncertain so MCP/LLM can validate it later.
                            xssUncertain = true;
                        }
                    }
                    
                    if (xssReflectionHit || xssUncertain) {
                        isInjectionFinding = true;
                        extractedContext = extractContext(bodyStr, payload, 60);
                        injectionDesc = I18n.t("module.pv.finding.desc.xss", extractedContext);
                        if (xssUncertain) {
                            injectionDesc += "\n[UNCERTAIN] " + I18n.t("module.pv.finding.desc.xss_uncertain");
                        }
                    }
                }
            }

            // Canary probes carry `'"` and `<>`. If one comes back byte-for-byte (not
            // HTML-entity-escaped), the parameter echoes attacker input unsanitised into the
            // response — an injection point worth a manual XSS/HTMLi look even though the canary
            // itself isn't a working payload. Escaped reflection produces no match, so no noise.
            if (mutation.type().equals("CANARY_PROBE") && !isInjectionFinding
                    && mutation.value() instanceof String canary && bodyStr.contains(canary)) {
                String ct = mutatedResponse.headerValue("Content-Type");
                boolean textualBody = ct == null || ct.toLowerCase().contains("html") || ct.toLowerCase().contains("xml");
                if (textualBody) {
                    isInjectionFinding = true;
                    injectionSeverity = Severity.LOW;
                    extractedContext = extractContext(bodyStr, canary, 60);
                    injectionDesc = I18n.t("module.pv.finding.desc.canary_reflected", extractedContext);
                }
            }

            String bodyStrLower = bodyStr.toLowerCase();

            boolean cmdiHit = false;
            if (mutation.type().equals("STRING_CMDI")) {
                String match = firstMatch(bodyStrLower, CMDI_SIGNATURES);
                if (match != null && !baselineBodyLower.contains(match)) {
                    cmdiHit = true;
                    isInjectionFinding = true;
                    extractedContext = extractContext(bodyStr, match, 60);
                    injectionDesc = I18n.t("module.pv.finding.desc.cmdi", match, extractedContext);
                }
            }

            boolean sstiHit = false;
            if (mutation.type().equals("STRING_SSTI") && mutation.value() instanceof String sstiPayload) {
                String evaluated = SSTI_EXPECTED.get(sstiPayload);
                if (evaluated != null && bodyStr.contains(evaluated) && !baselineBodyLower.contains(evaluated.toLowerCase())) {
                    sstiHit = true;
                    isInjectionFinding = true;
                    extractedContext = extractContext(bodyStr, evaluated, 60);
                    injectionDesc = I18n.t("module.pv.finding.desc.ssti", sstiPayload, evaluated, extractedContext);
                }
            }

            boolean ssrfHeuristicHit = false;
            if (mutation.type().equals("STRING_SSRF_HEURISTIC")) {
                String match = firstMatch(bodyStrLower, SSRF_SIGNATURES);
                if (match != null && !baselineBodyLower.contains(match)) {
                    ssrfHeuristicHit = true;
                    isInjectionFinding = true;
                    injectionSeverity = Severity.CRITICAL;
                    extractedContext = extractContext(bodyStr, match, 60);
                    injectionDesc = I18n.t("module.pv.finding.desc.ssrf_heuristic", mutation.value(), match, extractedContext);
                }
            }

            // Single-identity heuristic: confirms the endpoint accepts a neighboring ID with a
            // similarly-shaped 2xx response, not that it actually leaked another user's data —
            // this session has only one auth context to test with, so it can't confirm
            // cross-tenant access on its own. Flagged for manual verification with a second account.
            boolean idorHit = false;
            if (mutation.type().equals("IDOR_ADJACENT_ID") && status >= 200 && status <= 299) {
                double idorDiffRatio = baselineLength <= 0 ? 1.0 : Math.abs(length - baselineLength) / (double) baselineLength;
                if (idorDiffRatio < 0.5) {
                    idorHit = true;
                    isInjectionFinding = true;
                    injectionSeverity = Severity.HIGH;
                    injectionDesc = I18n.t("module.pv.finding.desc.idor", mutation.path(), mutation.value(), status, length, baselineLength);
                }
            }

            // Boolean-based SQLi ('OR '1'='1') leaves no error string and no timing tell —
            // the only signal is that the payload changed which rows came back. Every other
            // injection class above has a dedicated hit check; STRING_SQLI previously had
            // none and only ever got flagged by the opt-in "Behavioral Analysis" toggle
            // (off by default), so a real boolean-based SQLi went unreported out of the box.
            boolean booleanSqliHit = false;
            if (mutation.type().equals("STRING_SQLI") && requireBaseline && status >= 200 && status <= 299) {
                double sqliDiffRatio = baselineLength <= 0 ? 0 : Math.abs(length - baselineLength) / (double) baselineLength;
                if (sqliDiffRatio > 0.20) {
                    booleanSqliHit = true;
                    isInjectionFinding = true;
                    injectionDesc = I18n.t("module.pv.finding.desc.boolean_sqli", mutation.value(), length, baselineLength);
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
                        injectionDesc = I18n.t("module.pv.finding.desc.backend_error", verboseMatch);
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
                        injectionDesc = I18n.t("module.pv.finding.desc.size_anomaly", length, baselineLength);
                    } else if (baselineTime > 0 && responseTime > baselineTime * 5 && responseTime > 3000) {
                        isInjectionFinding = true;
                        injectionSeverity = Severity.MEDIUM;
                        injectionDesc = I18n.t("module.pv.finding.desc.time_anomaly", responseTime, baselineTime);
                    }
                }
            }

            // ── §3: status-transition detection (behind pv.status_transition_detection) ──
            // Runs after the dedicated per-type detectors, never downgrades a confirmed hit.
            if (config.getBool("pv.status_transition_detection", false)
                    && baselineStable && !isInjectionFinding
                    && baselineStatus > 0 && status != baselineStatus) {
                // Verbose-error regex only for 5xx (skip it on every 2xx mutation).
                boolean bodyHasVerboseError = status >= 500
                        && VerboseErrorDetector.getVerboseErrorMatch(bodyStr) != null;
                StatusTransition.Transition t = StatusTransition.classifyTransition(
                        baselineStatus, status, mutation.category(), mutation.remove(),
                        baselineStable, true, behavioralAnalysis, bodyHasVerboseError);
                if (t == StatusTransition.Transition.SESSION_LOST) {
                    if (!sessionLostLogged) {
                        sessionLostLogged = true;
                        logger.accept(I18n.t("module.pv.log.session_lost", baselineStatus, status));
                    }
                } else if (t != StatusTransition.Transition.NONE) {
                    boolean bypass = t == StatusTransition.Transition.BYPASS;
                    String transitionClass = bypass ? "bypass" : "error";
                    if (statusTransitionsSeen.add(mutation.path() + "|" + transitionClass)) {
                        isInjectionFinding = true;
                        injectionSeverity = Severity.MEDIUM;
                        injectionDesc = I18n.t("module.pv.finding.desc.status_transition",
                                baselineStatus, status,
                                I18n.t(bypass ? "module.pv.finding.desc.status_bypass"
                                              : "module.pv.finding.desc.status_error"));
                    }
                }
            }

            // Immediately spin out dedicated Injection Findings for pentester review.
            // Named by mutation.type() (e.g. STRING_XSS, STRING_SQLI_TIME) so distinct
            // injection classes on the same parameter don't collide under one shared name.
            if (isInjectionFinding) {
                findings.add(Finding.builder(name(), mutation.type())
                        .description(I18n.t("module.pv.finding.desc.injection", mutation.path(), mutation.value(), injectionDesc))
                        .severity(injectionSeverity)
                        .category(mutation.category())
                        .path(mutation.path())
                        .evidence(mutatedResult)
                        .meta("status", String.valueOf(status))
                        .meta("length", String.valueOf(length))
                        .meta("responseTime", String.valueOf(responseTime))
                        .meta("context", extractedContext)
                        .meta("payload", String.valueOf(mutation.value()))
                        .meta("baselineLength", String.valueOf(baselineLength))
                        .build());
                logger.accept(I18n.t("module.pv.log.finding_injection", shortPath(mutation.path()), mutation.type(), status));
                continue; // Skip adding to the grouped validation bucket
            }

            // ── Standard Validation Logic ──
            boolean accepted = (status >= findingStatusMin && status <= findingStatusMax);

            if (accepted && filterExactMatch && length == baselineLength) {
                accepted = false;
            }

            if (accepted) {
                Severity severity = Severity.MEDIUM;
                String findingDesc = I18n.t("module.pv.finding.desc.validation_mutation", mutation.description(), status, length);

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

            String findingName = I18n.t("module.pv.finding.name.validation");
            StringBuilder desc = new StringBuilder(I18n.t("module.pv.finding.desc.validation_base", path));

            if (structuralFailure) {
                desc.append(I18n.t("module.pv.finding.desc.validation_structural"));
            }
            if (typeFailure) {
                desc.append(I18n.t("module.pv.finding.desc.validation_type"));
            }
            if (boundaryFailure) {
                desc.append(I18n.t("module.pv.finding.desc.validation_boundary"));
            }

            // Per-mutation detail — keeps the specific payload/status/size a pentester
            // needs to reproduce each bypass, instead of only the rolled-up summary above.
            desc.append(I18n.t("module.pv.finding.desc.validation_payloads"));
            for (MutationResult res : pathFindings) {
                desc.append(I18n.t("module.pv.finding.desc.validation_payload_item", res.mutation().type(), res.desc()));
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

        findings.addAll(correlateSsrfInteractions(collaboratorClient, ssrfPayloadsByField, mutations, responses, config, logger));

        Map<String, List<Finding>> groupedFindings = findings.stream()
                .collect(Collectors.groupingBy(f -> {
                    String fullPath = f.evidence() != null && f.evidence().request() != null ? f.evidence().request().path() : f.path();
                    String urlPath = fullPath.contains("?") ? fullPath.substring(0, fullPath.indexOf('?')) : fullPath;
                    return f.type() + "|||" + urlPath;
                }));

        List<Finding> finalFindings = new ArrayList<>();
        
        for (Map.Entry<String, List<Finding>> entry : groupedFindings.entrySet()) {
            List<Finding> group = entry.getValue();
            if (group.size() > 1) {
                Finding first = group.get(0);
                String fullPath = first.evidence() != null && first.evidence().request() != null ? first.evidence().request().path() : first.path();
                String urlPath = fullPath.contains("?") ? fullPath.substring(0, fullPath.indexOf('?')) : fullPath;
                
                final int[] choice = new int[1];
                try {
                    SwingUtilities.invokeAndWait(() -> {
                        choice[0] = JOptionPane.showConfirmDialog(
                            null,
                            I18n.t("module.pv.ui.combine_findings_msg", group.size(), first.type(), urlPath),
                            I18n.t("module.pv.ui.combine_findings_title"),
                            JOptionPane.YES_NO_OPTION
                        );
                    });
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    choice[0] = JOptionPane.NO_OPTION;
                } catch (Exception e) {
                    choice[0] = JOptionPane.NO_OPTION;
                }
                
                if (choice[0] == JOptionPane.YES_OPTION) {
                    StringBuilder combinedDesc = new StringBuilder(I18n.t("module.pv.finding.desc.combined_base", urlPath));
                    Finding mostSevere = group.get(0);
                    for (Finding f : group) {
                        if (f.severity().compareTo(mostSevere.severity()) < 0) {
                            mostSevere = f;
                        }
                        combinedDesc.append(I18n.t("module.pv.finding.desc.combined_param", f.path()));
                        combinedDesc.append(f.description()).append("\n\n");
                    }
                    
                    var aggregatedBuilder = Finding.builder(name(), first.type())
                        .description(combinedDesc.toString().trim())
                        .severity(mostSevere.severity())
                        .category(mostSevere.category())
                        .path(urlPath)
                        .evidence(mostSevere.evidence());
                    mostSevere.metadata().forEach(aggregatedBuilder::meta);
                    finalFindings.add(aggregatedBuilder.build());
                } else {
                    finalFindings.addAll(group);
                }
            } else {
                finalFindings.addAll(group);
            }
        }
        
        findings = finalFindings;

        return findings;
    }

    /**
     * Out-of-band SSRF confirmation: a Collaborator interaction is proof the target actually
     * made an outbound request to a payload only this scan knows about — the highest-confidence
     * signal in this whole module, unlike every response-content heuristic above which can only
     * ever be circumstantial. Interactions can arrive with some delay after the triggering
     * request, so this waits once (not per-payload) before polling, rather than racing the check
     * against requests still in flight to Collaborator's infrastructure.
     */
    private List<Finding> correlateSsrfInteractions(CollaboratorClient client, Map<String, CollaboratorPayload> payloadsByField,
                                                      List<Mutation> mutations, List<HttpRequestResponse> responses,
                                                      ModuleConfig config, Consumer<String> logger) {
        if (client == null || payloadsByField.isEmpty()) return List.of();

        int waitMs = config.getInt("pv.ssrf_collaborator_wait_ms", 5000);
        logger.accept(I18n.t("module.pv.log.wait_collaborator", waitMs));
        try {
            Thread.sleep(waitMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
        }

        List<Interaction> interactions;
        try {
            interactions = client.getAllInteractions();
        } catch (Exception e) {
            logger.accept(I18n.t("module.pv.log.collab_poll_failed", e.getMessage()));
            return List.of();
        }
        if (interactions.isEmpty()) return List.of();

        List<Finding> findings = new ArrayList<>();
        for (var entry : payloadsByField.entrySet()) {
            String path = entry.getKey();
            String payloadId = entry.getValue().id().toString();

            for (Interaction interaction : interactions) {
                if (!interaction.id().toString().equals(payloadId)) continue;

                int mutationIndex = -1;
                for (int i = 0; i < mutations.size(); i++) {
                    if (mutations.get(i).path().equals(path) && mutations.get(i).type().equals("STRING_SSRF_OOB")) {
                        mutationIndex = i;
                        break;
                    }
                }
                if (mutationIndex < 0 || mutationIndex >= responses.size() || responses.get(mutationIndex) == null) continue;
                HttpRequestResponse evidence = responses.get(mutationIndex);

                findings.add(Finding.builder(name(), "STRING_SSRF")
                        .description(I18n.t("module.pv.finding.desc.ssrf_oob", path, interaction.type()))
                        .severity(Severity.CRITICAL)
                        .category(Category.INJECTION)
                        .path(path)
                        .evidence(evidence)
                        .meta("interactionType", interaction.type().name())
                        .build());
                logger.accept(I18n.t("module.pv.log.finding_ssrf_collab", shortPath(path), interaction.type()));
                break;
            }
        }
        return findings;
    }

    /**
     * SSRF and IDOR mutations need the field's name/path (to target ID-shaped fields, and to key
     * SSRF's Collaborator payload map for later correlation) that {@link SpecsFactory#specsFor}
     * doesn't have — generated here instead of through its pure value-only dispatch.
     */
    private List<MutationSpec> contextualSpecs(String pathString, Object leafValue, ModuleConfig config,
                                                CollaboratorClient collaboratorClient, Map<String, CollaboratorPayload> ssrfPayloadsByField,
                                                String depth) {
        List<MutationSpec> specs = new ArrayList<>();

        if (config.getBool("pv.ssrf", true) && leafValue instanceof String) {
            if (collaboratorClient != null) {
                CollaboratorPayload payload = collaboratorClient.generatePayload();
                specs.add(new MutationSpec("STRING_SSRF_OOB", I18n.t("module.pv.spec.desc.ssrf_oob"), "http://" + payload + "/", false, Category.INJECTION));
                ssrfPayloadsByField.put(pathString, payload);
            } else {
                for (String target : payloadsFor("pv.payload_ssrf_heuristic", PayloadRepository.SSRF_HEURISTIC_DEFAULT, depth, config)) {
                    specs.add(new MutationSpec("STRING_SSRF_HEURISTIC", I18n.t("module.pv.spec.desc.ssrf_heuristic"), target, false, Category.INJECTION));
                }
            }
        }

        if (config.getBool("pv.idor", true) && pathString != null && pathString.matches("(?i).*\\b(id|uuid|guid)s?\\b.*")) {
            if (leafValue instanceof Long l) {
                specs.add(new MutationSpec("IDOR_ADJACENT_ID", I18n.t("module.pv.spec.desc.adjacent_id"), l + 1, false, Category.ACCESS_CONTROL));
            } else if (leafValue instanceof RawNumber rn && rn.isInteger()) {
                try {
                    specs.add(new MutationSpec("IDOR_ADJACENT_ID", I18n.t("module.pv.spec.desc.adjacent_id"), Long.parseLong(rn.value()) + 1, false, Category.ACCESS_CONTROL));
                } catch (NumberFormatException ignored) {
                    // Not actually a plain integer literal despite isInteger() — leave the field alone rather than guess.
                }
            } else if (leafValue instanceof String s && s.matches("[0-9a-fA-F-]{8,36}")) {
                // UUID/opaque-id-shaped string: probe a fixed, plausible alternate id rather than
                // guessing a structure we can't safely mutate.
                specs.add(new MutationSpec("IDOR_ADJACENT_ID", I18n.t("module.pv.spec.desc.alternate_id"), "00000000-0000-0000-0000-000000000001", false, Category.ACCESS_CONTROL));
            }
        }

        return specs;
    }

    /** Parses a Retry-After header (either delay-seconds or an HTTP-date) into milliseconds, or -1 if absent/unparseable. */
    private static long parseRetryAfterMs(String headerValue) {
        if (headerValue == null || headerValue.isBlank()) return -1;
        String trimmed = headerValue.trim();
        try {
            return Long.parseLong(trimmed) * 1000L;
        } catch (NumberFormatException notSeconds) {
            try {
                var when = java.time.ZonedDateTime.parse(trimmed, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME);
                return Math.max(0, when.toInstant().toEpochMilli() - System.currentTimeMillis());
            } catch (Exception notADate) {
                return -1;
            }
        }
    }

    // Command-output signatures for common *nix/Windows commands used in the STRING_CMDI
    // payloads above (`id`, `whoami`) — kept short and specific rather than exhaustive, to
    // avoid false positives on legitimate response content.
    public static final List<String> CMDI_SIGNATURES = List.of(
            "uid=", "gid=", "groups=", "root:x:0:0", "www-data",
            "volume serial number", "directory of ");

    // Cloud metadata endpoint response fields — reaching these (via the STRING_SSRF_HEURISTIC
    // payloads above) is a strong signal the server followed the URL into an internal network,
    // not just that it echoed the payload back.
    public static final List<String> SSRF_SIGNATURES = List.of(
            "ami-id", "instance-id", "iam/security-credentials", "computemetadata", "instance/service-accounts");

    // Only covers the default STRING_SSTI payloads (see SpecsFactory) — a custom pv.payload_ssti
    // value won't have a known expected result to check against, so SSTI detection silently
    // skips it rather than guessing what evaluation would look like.
    public static final Map<String, String> SSTI_EXPECTED = Map.of(
            "${7*7}", "49", "{{7*7}}", "49", "#{7*7}", "49", "<%= 7*7 %>", "49");

    /** First signature (already lowercase) present in {@code bodyLower}, or null if none match. */
    public static String firstMatch(String bodyLower, List<String> signatures) {
        for (String sig : signatures) {
            if (bodyLower.contains(sig)) return sig;
        }
        return null;
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
        static List<MutationSpec> specsFor(Object value, ModuleConfig config, String depth) {
            List<MutationSpec> specs = new ArrayList<>();

            boolean testStructural = config.getBool("pv.structural", true);
            boolean testTypeConfusion = config.getBool("pv.type_confusion", true);
            boolean testBoundary = config.getBool("pv.boundary", true);
            boolean testInjection = config.getBool("pv.injection", true);

            if (testStructural) {
                if (config.getBool("pv.null_value", true)) {
                    specs.add(new MutationSpec("NULL_VALUE", I18n.t("module.pv.spec.desc.null_value"), null, false, Category.STRUCTURAL));
                }
                if (config.getBool("pv.field_removal", true)) {
                    specs.add(new MutationSpec("FIELD_REMOVED", I18n.t("module.pv.spec.desc.field_removed"), null, true, Category.STRUCTURAL));
                }
                if (config.getBool("pv.empty_object", true)) {
                    specs.add(new MutationSpec("TYPE_EMPTY_OBJECT", I18n.t("module.pv.spec.desc.empty_object"), new LinkedHashMap<>(), false, Category.STRUCTURAL));
                }
                if (config.getBool("pv.empty_array", true)) {
                    specs.add(new MutationSpec("TYPE_EMPTY_ARRAY", I18n.t("module.pv.spec.desc.empty_array"), new ArrayList<>(), false, Category.STRUCTURAL));
                }
            }

            if (value instanceof String) {
                if (testBoundary) {
                    if (config.getBool("pv.empty_string", true)) {
                        specs.add(new MutationSpec("EMPTY_STRING", I18n.t("module.pv.spec.desc.empty_string"), "", false, Category.BOUNDARY));
                    }
                    if (config.getBool("pv.long_string", true)) {
                        int len = config.getInt("pv.long_string_length", 10000);
                        specs.add(new MutationSpec("STRING_LONG", I18n.t("module.pv.spec.desc.long_string", len), "A".repeat(len), false, Category.BOUNDARY));
                    }
                }
                if (testInjection) {
                    // Canary Probes First
                    for (String payload : icarus.modules.PayloadRepository.CANARY_PROBES) {
                        specs.add(new MutationSpec("CANARY_PROBE", icarus.core.I18n.t("module.pv.spec.desc.canary_probe"), payload, false, Category.INJECTION));
                    }

                    if (config.getBool("pv.sqli", true)) {
                        for (String p : payloadsFor("pv.payload_sqli", PayloadRepository.SQLI_DEFAULT, depth, config)) {
                            specs.add(new MutationSpec("STRING_SQLI", I18n.t("module.pv.spec.desc.sqli"), p, false, Category.INJECTION));
                        }
                    }
                    if (config.getBool("pv.sqli_time", true)) {
                        for (String payload : payloadsFor("pv.payload_sqli_time", PayloadRepository.SQLI_TIME_DEFAULT, depth, config)) {
                            specs.add(new MutationSpec("STRING_SQLI_TIME", I18n.t("module.pv.spec.desc.sqli_time"), payload, false, Category.INJECTION));
                        }
                    }
                    if (config.getBool("pv.xss", true)) {
                        for (String payload : payloadsFor("pv.payload_xss", PayloadRepository.XSS_DEFAULT, depth, config)) {
                            specs.add(new MutationSpec("STRING_XSS", I18n.t("module.pv.spec.desc.xss"), payload, false, Category.INJECTION));
                        }
                    }
                    if (config.getBool("pv.path_traversal", true)) {
                        for (String payload : payloadsFor("pv.payload_path_traversal", PayloadRepository.PATH_TRAVERSAL_DEFAULT, depth, config)) {
                            specs.add(new MutationSpec("STRING_PATH_TRAVERSAL", I18n.t("module.pv.spec.desc.path_traversal"), payload, false, Category.INJECTION));
                        }
                    }
                    if (config.getBool("pv.nosqli", true)) {
                        for (String payload : payloadsFor("pv.payload_nosqli", PayloadRepository.NOSQLI_DEFAULT, depth, config)) {
                            specs.add(new MutationSpec("STRING_NOSQLI", I18n.t("module.pv.spec.desc.nosqli"), payload, false, Category.INJECTION));
                        }
                    }
                    if (config.getBool("pv.format_string", true)) {
                        for (String payload : payloadsFor("pv.payload_format_string", PayloadRepository.FORMAT_STRING_DEFAULT, depth, config)) {
                            specs.add(new MutationSpec("STRING_FORMAT", I18n.t("module.pv.spec.desc.format_string"), payload, false, Category.INJECTION));
                        }
                    }
                    if (config.getBool("pv.unicode", true)) {
                        // Unicode is a single value, not a list: take the first non-empty trimmed line only.
                        List<String> uni = splitPayloads(config.getString("pv.payload_unicode", PayloadRepository.UNICODE_DEFAULT));
                        if (!uni.isEmpty()) {
                            specs.add(new MutationSpec("STRING_UNICODE", I18n.t("module.pv.spec.desc.unicode"), uni.get(0), false, Category.INJECTION));
                        }
                    }
                    if (config.getBool("pv.cmdi", true)) {
                        for (String payload : payloadsFor("pv.payload_cmdi", PayloadRepository.CMDI_DEFAULT, depth, config)) {
                            specs.add(new MutationSpec("STRING_CMDI", I18n.t("module.pv.spec.desc.cmdi"), payload, false, Category.INJECTION));
                        }
                    }
                    if (config.getBool("pv.ssti", true)) {
                        for (String payload : payloadsFor("pv.payload_ssti", PayloadRepository.SSTI_DEFAULT, depth, config)) {
                            specs.add(new MutationSpec("STRING_SSTI", I18n.t("module.pv.spec.desc.ssti"), payload, false, Category.INJECTION));
                        }
                    }
                }
                if (testTypeConfusion) {
                    if (config.getBool("pv.string_as_number", true)) {
                        specs.add(new MutationSpec("TYPE_NUMBER", I18n.t("module.pv.spec.desc.string_as_number"), 0L, false, Category.TYPE_CONFUSION));
                    }
                    if (config.getBool("pv.string_as_boolean", true)) {
                        specs.add(new MutationSpec("TYPE_BOOLEAN", I18n.t("module.pv.spec.desc.string_as_boolean"), Boolean.TRUE, false, Category.TYPE_CONFUSION));
                    }
                }
            } else if (value instanceof Long || value instanceof Integer
                    || (value instanceof RawNumber rn && rn.isInteger())) {
                if (testBoundary) {
                    if (config.getBool("pv.number_zero", true)) {
                        specs.add(new MutationSpec("NUMBER_ZERO", I18n.t("module.pv.spec.desc.zero_value"), 0L, false, Category.BOUNDARY));
                    }
                    if (config.getBool("pv.number_negative", true)) {
                        specs.add(new MutationSpec("NUMBER_NEGATIVE", I18n.t("module.pv.spec.desc.negative_value"), -1L, false, Category.BOUNDARY));
                    }
                    if (config.getBool("pv.number_overflow", true)) {
                        specs.add(new MutationSpec("NUMBER_OVERFLOW", I18n.t("module.pv.spec.desc.overflow"), Long.MAX_VALUE, false, Category.BOUNDARY));
                    }
                    if (config.getBool("pv.integer_as_float", true)) {
                        specs.add(new MutationSpec("NUMBER_FLOAT", I18n.t("module.pv.spec.desc.integer_as_float"), 1.5, false, Category.BOUNDARY));
                    }
                }
                if (testTypeConfusion) {
                    if (config.getBool("pv.number_as_string", true)) {
                        specs.add(new MutationSpec("TYPE_STRING", I18n.t("module.pv.spec.desc.number_as_string"), "abc", false, Category.TYPE_CONFUSION));
                    }
                    if (config.getBool("pv.number_as_numeric_string", true)) {
                        specs.add(new MutationSpec("TYPE_STRING_NUMERIC", I18n.t("module.pv.spec.desc.number_as_numeric_string"), "123", false, Category.TYPE_CONFUSION));
                    }
                }
            } else if (value instanceof Double || (value instanceof RawNumber rn && !rn.isInteger())) {
                if (testBoundary) {
                    if (config.getBool("pv.number_zero", true)) {
                        specs.add(new MutationSpec("NUMBER_ZERO", I18n.t("module.pv.spec.desc.zero_value"), 0.0, false, Category.BOUNDARY));
                    }
                    if (config.getBool("pv.number_negative", true)) {
                        specs.add(new MutationSpec("NUMBER_NEGATIVE", I18n.t("module.pv.spec.desc.negative_value"), -1.5, false, Category.BOUNDARY));
                    }
                }
                if (testInjection) {
                    if (config.getBool("pv.sqli", true)) {
                        specs.add(new MutationSpec("NUMBER_SQLI_MATH", I18n.t("module.pv.spec.desc.sqli_math"), "1/0", false, Category.INJECTION));
                    }
                    if (config.getBool("pv.sqli_time", true)) {
                        for (String payload : payloadsFor("pv.payload_sqli_time_number", PayloadRepository.SQLI_TIME_NUMBER_DEFAULT, depth, config)) {
                            specs.add(new MutationSpec("STRING_SQLI_TIME", I18n.t("module.pv.spec.desc.sqli_time_num"), payload, false, Category.INJECTION));
                        }
                    }
                }
                if (testTypeConfusion && config.getBool("pv.number_as_string", true)) {
                    specs.add(new MutationSpec("TYPE_STRING", I18n.t("module.pv.spec.desc.number_as_string_generic"), "abc", false, Category.TYPE_CONFUSION));
                }
            } else if (value instanceof Boolean b) {
                if (testBoundary && config.getBool("pv.boolean_flip", true)) {
                    specs.add(new MutationSpec("BOOLEAN_FLIP", I18n.t("module.pv.spec.desc.boolean_flip"), !b, false, Category.BOUNDARY));
                }
                if (testTypeConfusion) {
                    if (config.getBool("pv.boolean_as_string", true)) {
                        specs.add(new MutationSpec("TYPE_STRING", I18n.t("module.pv.spec.desc.boolean_as_string"), "true", false, Category.TYPE_CONFUSION));
                    }
                    if (config.getBool("pv.boolean_as_number", true)) {
                        specs.add(new MutationSpec("TYPE_NUMBER", I18n.t("module.pv.spec.desc.boolean_as_number"), 1L, false, Category.TYPE_CONFUSION));
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
