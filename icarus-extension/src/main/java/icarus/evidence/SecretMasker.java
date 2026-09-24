package icarus.evidence;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Masks live credentials in evidence text before it is rendered into a report screenshot —
 * session cookies, bearer tokens, API keys and password/token fields would otherwise ship to
 * the client in every deliverable, readable by anyone the report is forwarded to. Pure string
 * transformation over the editable request/response text (the same text the "Redact" context
 * menu edits), so the user still sees and can undo/adjust the result before applying.
 */
public final class SecretMasker {

    private SecretMasker() {}

    static final String REDACTED = "[REDACTED]";

    /** Headers whose whole value is a credential. */
    private static final Set<String> SECRET_HEADERS = Set.of(
            "authorization", "proxy-authorization", "x-api-key", "api-key", "x-auth-token",
            "x-access-token", "x-csrf-token", "x-xsrf-token", "x-amz-security-token");

    /** Field names (JSON keys, form/query params) whose value is a credential. */
    private static final String SECRET_KEYS =
            "password|passwd|pwd|pass|secret|client_secret|token|access_token|refresh_token|id_token"
            + "|api_key|apikey|auth|session|sessionid|session_id|jsessionid|phpsessid|otp|pin";

    private static final Pattern JSON_FIELD = Pattern.compile(
            "(\"(?i:" + SECRET_KEYS + ")\"\\s*:\\s*\")((?:[^\"\\\\]|\\\\.)*)(\")");
    private static final Pattern FORM_FIELD = Pattern.compile(
            "((?:^|[?&;\\s])(?i:" + SECRET_KEYS + ")=)([^&\\s#]*)");

    /** Masks credentials in a raw HTTP request or response (headers + body). */
    public static String mask(String text) {
        if (text == null || text.isEmpty()) return text;
        String[] lines = text.split("\n", -1);
        boolean inHeaders = true;
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (inHeaders && line.strip().isEmpty() && i > 0) inHeaders = false;
            String out;
            if (i == 0) {
                out = maskFields(line); // request line: query-string params
            } else if (inHeaders) {
                out = maskHeader(line);
            } else {
                out = maskFields(line);
            }
            sb.append(out);
            if (i < lines.length - 1) sb.append('\n');
        }
        return sb.toString();
    }

    private static String maskHeader(String line) {
        int colon = line.indexOf(':');
        if (colon <= 0) return line;
        String name = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
        String value = line.substring(colon + 1).trim();
        String prefix = line.substring(0, colon + 1) + " ";
        if (value.isEmpty()) return line;

        if (SECRET_HEADERS.contains(name)) {
            int sp = value.indexOf(' ');
            // Keep the auth scheme ("Bearer", "Basic") — it's useful context, not a secret.
            if (sp > 0 && sp < 16) return prefix + value.substring(0, sp + 1) + redact(value.substring(sp + 1).trim());
            return prefix + redact(value);
        }
        if (name.equals("cookie")) {
            StringBuilder sb = new StringBuilder();
            for (String pair : value.split(";")) {
                if (sb.length() > 0) sb.append("; ");
                sb.append(maskPair(pair.trim()));
            }
            return prefix + sb;
        }
        if (name.equals("set-cookie")) {
            int semi = value.indexOf(';');
            String first = semi >= 0 ? value.substring(0, semi) : value;
            return prefix + maskPair(first.trim()) + (semi >= 0 ? value.substring(semi) : "");
        }
        return line;
    }

    private static String maskPair(String pair) {
        int eq = pair.indexOf('=');
        if (eq < 0) return pair;
        return pair.substring(0, eq + 1) + redact(pair.substring(eq + 1));
    }

    private static String maskFields(String line) {
        Matcher m = JSON_FIELD.matcher(line);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(m.group(1) + redact(m.group(2)) + m.group(3)));
        }
        m.appendTail(sb);
        m = FORM_FIELD.matcher(sb.toString());
        StringBuilder sb2 = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(sb2, Matcher.quoteReplacement(m.group(1) + redact(m.group(2))));
        }
        m.appendTail(sb2);
        return sb2.toString();
    }

    /**
     * Long values keep a 4-char prefix so the reader can still tell tokens apart (and match
     * them to server logs); short ones — passwords, PINs — are fully hidden.
     */
    static String redact(String v) {
        if (v == null || v.isEmpty() || v.equals(REDACTED) || v.endsWith("…" + REDACTED)) return v;
        return v.length() >= 16 ? v.substring(0, 4) + "…" + REDACTED : REDACTED;
    }

    /** {@code java -ea -cp build_manual/classes icarus.evidence.SecretMasker} */
    public static void main(String[] args) {
        String req = "POST /login?next=/home&token=abcdefghijklmnopqrstu HTTP/1.1\n"
                + "Host: example.com\n"
                + "Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.payload.sig\n"
                + "Cookie: sid=0123456789abcdef0123; theme=dark\n"
                + "User-Agent: test\n"
                + "\n"
                + "{\"username\":\"alice\",\"password\":\"hunter2\",\"note\":\"Authorization: keep\"}";
        String out = mask(req);
        assert out.contains("token=abcd…[REDACTED]") : out;
        assert out.contains("next=/home") : out;
        assert out.contains("Authorization: Bearer eyJh…[REDACTED]") : out;
        assert out.contains("sid=0123…[REDACTED]; theme=[REDACTED]") : out;
        assert out.contains("User-Agent: test") : out;
        assert out.contains("\"password\":\"[REDACTED]\"") && out.contains("\"username\":\"alice\"") : out;
        assert out.contains("\"note\":\"Authorization: keep\"") : "body text must not be treated as a header: " + out;
        assert mask(out).equals(out) : "masking must be idempotent";

        String res = "HTTP/1.1 200 OK\nSet-Cookie: session=verylongsessionvalue1234; Path=/; HttpOnly\n\nok";
        assert mask(res).contains("Set-Cookie: session=very…[REDACTED]; Path=/; HttpOnly") : mask(res);
        System.out.println("SecretMasker self-check passed (run with -ea to enforce).");
    }
}
