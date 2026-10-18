package icarus.core;

import burp.api.montoya.core.ByteArray;

import java.util.List;
import java.util.regex.Pattern;

public class VerboseErrorDetector {

    /**
     * Cheap necessary-condition sentinels for {@link #mightContainVerboseError}. Every
     * DB/framework/language pattern below contains at least one of these as a substring, so a
     * body with none of them cannot match — letting the passive path skip the full
     * {@code bodyToString()} copy + ~35-regex pass on the common 200-OK-no-error case.
     * Known residual: a body whose ONLY error signal is a bare stack frame with no keyword
     * (e.g. a lone {@code at f (x.xyz:1:2)}) is no longer pre-matched.
     */
    private static final String[] SENTINELS = {
        "exception", "traceback", "stack trace", "stacktrace", "stack:",
        "error", "ora-", "sql", "mongo", "odbc", "jdbc", "oledb", "driver[",
        "fatal error", "warning:", "notice:", "parse error", "caused by",
        ", line ", ".rb:", ".js:", ".ts:", ".php", "java.", "at java",
        "org.spring", "org.apache", "werkzeug", "system.", "whitelabel",
        "jsonwebtoken", "jjwt", "nomethoderror", "unhandledpromise"
    };

    /**
     * True if {@code body} might contain a verbose error — a fast, allocation-free
     * (no String copy) case-insensitive substring scan over the raw bytes. When this returns
     * false, {@link #getVerboseErrorMatch} on the same body returns null (see {@link #SENTINELS}).
     */
    public static boolean mightContainVerboseError(ByteArray body) {
        if (body == null || body.length() == 0) return false;
        for (String s : SENTINELS) {
            if (body.indexOf(s, false) != -1) return true;
        }
        return false;
    }

    private static final List<Pattern> DB_ERROR_PATTERNS = List.of(
        Pattern.compile("(?i)ORA-\\d{5}:"), // Oracle
        Pattern.compile("(?i)SQL syntax.*MySQL"), // MySQL
        Pattern.compile("(?i)PostgreSQL query failed"), // PostgreSQL
        Pattern.compile("(?i)SQLite3::SQLException"), // SQLite (PHP)
        Pattern.compile("(?i)sqlite3\\.\\w*Error"), // SQLite (Python)
        Pattern.compile("(?i)SQLITE_(ERROR|CONSTRAINT|BUSY|MISUSE|CORRUPT|FULL|IOERR|LOCKED|READONLY|NOTADB)"), // SQLite raw error codes
        Pattern.compile("(?i)near \".*?\": syntax error"), // SQLite raw syntax error (language-agnostic, straight from libsqlite3)
        Pattern.compile("(?i)MongoError:"), // MongoDB
        Pattern.compile("(?i)(?:ODBC|JDBC|OLEDB) Driver\\[.*?\\]"), // ODBC/JDBC/OLEDB
        Pattern.compile("(?i)SQLServerException") // MSSQL
    );

    private static final List<Pattern> FRAMEWORK_AND_LANG_PATTERNS = List.of(
        // Java
        Pattern.compile("(?i)\\bjava\\.lang\\.\\w+Exception"),
        Pattern.compile("(?i)at java\\.base/"),
        // Qualified by an exception class or a stack frame — a bare "org.apache.xyz" also
        // appears in license notices, docs pages and Maven coordinates.
        Pattern.compile("(?i)(?:at |\\b)org\\.springframework\\.[\\w.$]*(?:Exception|Error)\\b|\\bat org\\.springframework\\.[\\w.$]+\\("),
        Pattern.compile("(?i)(?:at |\\b)org\\.apache\\.[\\w.$]*(?:Exception|Error)\\b|\\bat org\\.apache\\.[\\w.$]+\\("),

        // Python
        Pattern.compile("(?i)Traceback \\(most recent call last\\):"),
        Pattern.compile("(?i)File \"[^\"]+\", line \\d+, in"),
        Pattern.compile("(?i)werkzeug\\.exceptions\\."),

        // PHP
        Pattern.compile("(?i)Fatal error: Uncaught"),
        // Same-line only and bounded: an unbounded ".*" on a minified single-line body both
        // backtracks badly and drags the whole line into the finding description.
        Pattern.compile("(?i)Fatal error:[^\\r\\n]{0,300}? on line \\d+"),
        Pattern.compile("(?i)Warning:[^\\r\\n]{0,300}? on line \\d+"),
        Pattern.compile("(?i)Notice:[^\\r\\n]{0,300}? on line \\d+"),
        Pattern.compile("(?i)PHP (?:Warning|Notice|Parse error):"),
        // PHP prints "Stack trace:" then "#0 ..." on the next line. The old "\\bStack trace:\\b"
        // needed a word char right after the colon, so it never matched real PHP output; the
        // bare "stack ?trace" alternative instead matched any page merely mentioning the words.
        Pattern.compile("(?i)\\bStack trace:\\s*#\\d"),

        // Ruby
        Pattern.compile("(?i)\\w+\\.rb:\\d+:in"),
        Pattern.compile("(?i)NoMethodError:"),

        // Node.js / JavaScript
        Pattern.compile("(?i)TypeError: [^\\r\\n]{1,200}"),
        Pattern.compile("(?i)ReferenceError: [^\\r\\n]{1,200}"),
        Pattern.compile("(?i)SyntaxError: [^\\r\\n]{1,200}"),
        Pattern.compile("(?i)UnhandledPromiseRejection"),
        // Java/.NET chained cause — requires the class name, "Caused by:" alone is plain English.
        Pattern.compile("(?i)Caused by: [\\w.$]+(?:Exception|Error)\\b"),
        Pattern.compile("(?i)at [^()]*\\([^()]*:\\d+:\\d+\\)"), // V8 stacktrace (linear: no nested quantifier — see java/redos)

        // C# / ASP.NET
        Pattern.compile("(?i)System\\.\\w+Exception"),
        Pattern.compile("(?i)Server Error in '/' Application"),
        Pattern.compile("(?i)at System\\.Web\\.[a-zA-Z\\.]+"),

        // JWT / auth libraries
        // Error/exception names only. The previous bare-word "jose" matched the first name
        // (José/Jose) on any page, and "jsonwebtoken"/"jjwt" matched docs and package lists.
        Pattern.compile("(?i)\\bJsonWebTokenError\\b"),
        Pattern.compile("(?i)\\bio\\.jsonwebtoken\\.[\\w.$]*(?:Exception|Error)\\b"),
        Pattern.compile("(?i)\\bjjwt[\\w.$]*(?:Exception|Error)\\b"),
        Pattern.compile("(?i)\\bJOSE(?:Error|Exception)\\b"),

        // General / Web Servers
        Pattern.compile("(?i)Whitelabel Error Page")
    );

    /** Longest matched excerpt kept for a finding description / evidence caption. */
    private static final int MAX_MATCH_LENGTH = 240;

    /** Returns the matched string for reporting, or null if no match. */
    public static String getVerboseErrorMatch(String body) {
        if (body == null || body.isBlank()) return null;

        for (Pattern p : DB_ERROR_PATTERNS) {
            var m = p.matcher(body);
            if (m.find()) return "Database Error: " + clip(m.group());
        }
        for (Pattern p : FRAMEWORK_AND_LANG_PATTERNS) {
            var m = p.matcher(body);
            if (m.find()) return "Framework/Language Error: " + clip(m.group());
        }
        return null;
    }

    private static String clip(String match) {
        String oneLine = match.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= MAX_MATCH_LENGTH ? oneLine : oneLine.substring(0, MAX_MATCH_LENGTH) + "…";
    }

    /** `java -cp build_manual/libs/icarus-<version>.jar icarus.core.VerboseErrorDetector` — match + ReDoS self-check. */
    public static void main(String[] args) {
        assert getVerboseErrorMatch("at fn (/srv/app/index.js:10:5)") != null : "valid V8 frame must still match";
        assert getVerboseErrorMatch("ORA-00933: something") != null;
        assert getVerboseErrorMatch("<html>ok</html>") == null;
        assert getVerboseErrorMatch("PHP error\nStack trace:\n#0 /var/www/index.php(12)") != null : "PHP stack trace must match";
        assert getVerboseErrorMatch("<p>Contact Jose for a stack trace of the org.apache license</p>") == null : "prose must not match";
        assert getVerboseErrorMatch("Caused by: java.sql.SQLException: x") != null;
        assert getVerboseErrorMatch("x".repeat(10) + "TypeError: " + "y".repeat(5000)).length() < 300 : "match excerpt must be bounded";
        // Sentinel invariant: anything the full pass matches, the byte pre-filter must admit.
        for (String sample : new String[]{"Stack trace:\n#0 a", "io.jsonwebtoken.ExpiredJwtException", "JOSEError", "Caused by: a.BError",
                "at org.apache.catalina.core.X(Y.java:1)", "org.springframework.web.HttpMediaTypeNotSupportedException"}) {
            assert getVerboseErrorMatch(sample) != null : "sample should match: " + sample;
            // ByteArray needs the Burp runtime, so mirror mightContainVerboseError on a String.
            String lower = sample.toLowerCase();
            assert java.util.Arrays.stream(SENTINELS).anyMatch(lower::contains) : "sentinel pre-filter must admit: " + sample;
        }

        // The V8 pattern must be linear: a crafted body that never completes the frame must
        // not backtrack exponentially. Was: (?:.*/)*.* — catastrophic on 'at  (' + many '/'.
        String evil = "at  (" + "/".repeat(60_000);
        long start = System.nanoTime();
        getVerboseErrorMatch(evil);
        long ms = (System.nanoTime() - start) / 1_000_000;
        assert ms < 1_000 : "V8 stacktrace regex backtracked for " + ms + "ms on adversarial input (ReDoS)";

        System.out.println("VerboseErrorDetector self-check passed (run with -ea to enforce).");
    }
}
