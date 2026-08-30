package icarus.modules;

import burp.api.montoya.http.message.responses.HttpResponse;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Multi-vendor WAF / CDN fingerprinting from a single response (baseline or a
 * blocked one). Pure static utility — no state, no Montoya beyond {@link HttpResponse}.
 *
 * <p>Launch scope: the big 5 + GENERIC (see PLAN.md §7-B). Everything else falls
 * through to GENERIC (logged, not vendor-named).
 *
 * <p>{@link #looksBlocked} is a body-marker SUPPLEMENT to ParamValidatorModule's
 * existing status-code fast path (L510) — it does not re-implement that logic.
 */
public final class WafFingerprint {

    private WafFingerprint() {}

    /** Matching context adapted from a real response (or fabricated in the self-check). */
    static final class Sig {
        final Map<String, String> headers; // lowercased name -> value(s), duplicates joined by "\n"
        final String cookie;               // Set-Cookie value(s), lowercased
        final String body;                 // lowercased, never null
        final int status;

        Sig(Map<String, String> headers, String body, int status) {
            this.headers = new LinkedHashMap<>();
            headers.forEach((k, v) -> this.headers.put(k.toLowerCase(), v == null ? "" : v));
            this.cookie = this.headers.getOrDefault("set-cookie", "").toLowerCase();
            this.body = body == null ? "" : body.toLowerCase();
            this.status = status;
        }

        String hdr(String name) { return headers.getOrDefault(name.toLowerCase(), ""); }
        boolean hasHeaderPrefix(String prefix) {
            return headers.keySet().stream().anyMatch(k -> k.startsWith(prefix));
        }
        boolean hdrContains(String name, String needle) {
            return hdr(name).toLowerCase().contains(needle);
        }
        boolean bodyHas(String needle) { return body.contains(needle); }
    }

    // Generic WAF block-page markers (not vendor-specific).
    private static final List<String> GENERIC_BLOCK_MARKERS = List.of(
        "request has been blocked", "security policy", "web application firewall", "access denied"
    );
    // Vendor-specific body markers — also count towards looksBlocked().
    private static final List<String> VENDOR_BLOCK_MARKERS = List.of(
        "attention required", "error 1020", "reference #", "<accessdenied>",
        "incapsula incident id", "the requested url was rejected", "support id"
    );

    /** Body smaller than this on a 4xx is "far smaller than a normal app response". */
    private static final int BLOCK_BODY_MAX = 4096;

    private static final Map<WafVendor, List<Predicate<Sig>>> TABLE = buildTable();

    private static Map<WafVendor, List<Predicate<Sig>>> buildTable() {
        Map<WafVendor, List<Predicate<Sig>>> t = new LinkedHashMap<>();

        t.put(WafVendor.CLOUDFLARE, List.of(
            s -> s.hdrContains("server", "cloudflare") || !s.hdr("cf-ray").isEmpty(),
            s -> s.cookie.contains("__cf_bm") || s.cookie.contains("cf_clearance")
                 || s.bodyHas("attention required") || s.bodyHas("error 1020")
                 || s.hdrContains("server", "cloudflare")
        ));

        t.put(WafVendor.AKAMAI, List.of(
            s -> s.hasHeaderPrefix("ak-") || s.hasHeaderPrefix("x-akamai-")
                 || s.hdrContains("server", "akamai"),
            s -> s.hdrContains("server", "akamaighost")
                 || (s.bodyHas("reference #") && s.bodyHas("akamai"))
                 || s.hasHeaderPrefix("x-akamai-")
        ));

        t.put(WafVendor.AWS_WAF, List.of(
            s -> !s.hdr("x-amzn-requestid").isEmpty() || s.hdrContains("server", "awselb"),
            s -> !s.hdr("x-amzn-errortype").isEmpty() || s.bodyHas("<accessdenied>")
                 || s.hdrContains("server", "awselb")
        ));

        t.put(WafVendor.IMPERVA_INCAPSULA, List.of(
            s -> !s.hdr("x-iinfo").isEmpty()
                 || s.cookie.contains("incap_ses_") || s.cookie.contains("visid_incap_"),
            s -> s.bodyHas("incapsula incident id")
                 || s.cookie.contains("incap_ses_") || !s.hdr("x-iinfo").isEmpty()
        ));

        t.put(WafVendor.F5_BIGIP_ASM, List.of(
            s -> s.hdrContains("server", "bigip") || s.cookie.contains("bigipserver")
                 || s.bodyHas("the requested url was rejected"),
            s -> (s.bodyHas("the requested url was rejected") && s.bodyHas("support id"))
                 || s.cookie.contains("bigipserver") || s.hdrContains("server", "bigip")
        ));

        return t;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public static Optional<WafVendor> detect(HttpResponse resp) {
        Sig s = adapt(resp);
        return s == null ? Optional.empty() : detect(s);
    }

    public static boolean looksBlocked(HttpResponse resp) {
        Sig s = adapt(resp);
        return s != null && looksBlocked(s);
    }

    // ── Package-private matching core (self-check drives these directly) ───────

    static Optional<WafVendor> detect(Sig s) {
        for (Map.Entry<WafVendor, List<Predicate<Sig>>> e : TABLE.entrySet()) {
            if (e.getValue().stream().allMatch(p -> p.test(s))) {
                return Optional.of(e.getKey());
            }
        }
        return looksBlocked(s) ? Optional.of(WafVendor.GENERIC) : Optional.empty();
    }

    static boolean looksBlocked(Sig s) {
        if (s.status != 403 && s.status != 406) return false;
        if (s.body.isEmpty() || s.body.length() >= BLOCK_BODY_MAX) return false;
        return GENERIC_BLOCK_MARKERS.stream().anyMatch(s::bodyHas)
            || VENDOR_BLOCK_MARKERS.stream().anyMatch(s::bodyHas);
    }

    private static Sig adapt(HttpResponse resp) {
        if (resp == null) return null;
        String body;
        try {
            body = resp.bodyToString();
        } catch (RuntimeException ex) {
            body = null;
        }
        Map<String, String> headers = new LinkedHashMap<>();
        try {
            resp.headers().forEach(h -> headers.merge(h.name(), h.value(), (a, b) -> a + "\n" + b));
        } catch (RuntimeException ex) {
            // headers unavailable — match on body/status only
        }
        int status;
        try {
            status = resp.statusCode();
        } catch (RuntimeException ex) {
            status = 0;
        }
        return new Sig(headers, body, status);
    }

    // ── Self-check ────────────────────────────────────────────────────────────

    public static void main(String[] args) {
        Sig cf = new Sig(Map.of("Server", "cloudflare", "CF-RAY", "8abc123-LHR",
                "Set-Cookie", "__cf_bm=xyz; path=/"), "Attention Required! | Cloudflare", 403);
        assert detect(cf).equals(Optional.of(WafVendor.CLOUDFLARE)) : detect(cf);

        Sig ak = new Sig(Map.of("Server", "AkamaiGHost", "X-Akamai-Transformed", "9 - 0 -"),
                "Reference #18.abcd Akamai", 403);
        assert detect(ak).equals(Optional.of(WafVendor.AKAMAI)) : detect(ak);

        Sig aws = new Sig(Map.of("Server", "awselb/2.0", "x-amzn-RequestId", "abc",
                "x-amzn-ErrorType", "AccessDeniedException"), "<AccessDenied>", 403);
        assert detect(aws).equals(Optional.of(WafVendor.AWS_WAF)) : detect(aws);

        Sig imp = new Sig(Map.of("X-Iinfo", "12-34-56", "Set-Cookie", "visid_incap_123=abc"),
                "Incapsula incident ID: 999", 403);
        assert detect(imp).equals(Optional.of(WafVendor.IMPERVA_INCAPSULA)) : detect(imp);

        Sig f5 = new Sig(Map.of("Server", "BigIP", "Set-Cookie", "BIGipServerpool=123.456.789"),
                "The requested URL was rejected. Please consult with your administrator. Your support ID is: 42",
                403);
        assert detect(f5).equals(Optional.of(WafVendor.F5_BIGIP_ASM)) : detect(f5);

        Sig gen = new Sig(Map.of("Server", "nginx"),
                "Your request has been blocked by the security policy.", 403);
        assert detect(gen).equals(Optional.of(WafVendor.GENERIC)) : detect(gen);
        assert looksBlocked(gen);

        Sig ok = new Sig(Map.of("Server", "nginx", "Content-Type", "text/html"),
                "<html><body><h1>Welcome</h1><p>Normal application page with plenty of content.</p></body></html>",
                200);
        assert detect(ok).isEmpty() : detect(ok);
        assert !looksBlocked(ok);

        assert detect((HttpResponse) null).isEmpty();
        assert !looksBlocked((HttpResponse) null);

        System.out.println("WafFingerprint self-check OK");
    }
}
