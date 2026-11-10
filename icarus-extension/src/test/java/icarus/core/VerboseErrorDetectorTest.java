package icarus.core;

/**
 * The verbose-error patterns run on the single passive-analysis thread against bodies the
 * target controls, so a crafted body must not make any of them backtrack quadratically.
 * Each adversarial body is 1 MB and must finish in well under a second (the unbounded
 * patterns took 7 s on 80 KB).
 *
 * {@code java -cp build_manual/test-classes:build_manual/classes:libs/montoya-api-2026.7.jar icarus.core.VerboseErrorDetectorTest}
 */
public final class VerboseErrorDetectorTest {

    private static final int ONE_MB = 1024 * 1024;

    public static void main(String[] args) {
        realErrorsStillMatch();
        adversarialBodiesAreFast();
        System.out.println("VerboseErrorDetectorTest: all checks passed.");
    }

    private static void realErrorsStillMatch() {
        String[] samples = {
                "app/models/user.rb:42:in `find'",
                "    at fn (/srv/app/index.js:10:5)",
                "You have an error in your SQL syntax; check the manual that corresponds to your MySQL server",
                "near \"FROM\": syntax error",
                "[Microsoft][ODBC Driver[17] for SQL Server]",
                "at org.apache.catalina.core.StandardWrapper.invoke(StandardWrapper.java:1)",
                "org.springframework.web.HttpMediaTypeNotSupportedException",
                "File \"/srv/app.py\", line 12, in handler",
                "Caused by: java.sql.SQLException: boom",
                "java.lang.NullPointerException",
                "System.InvalidOperationException",
                "io.jsonwebtoken.ExpiredJwtException",
                "PHP error\nStack trace:\n#0 /var/www/index.php(12)",
        };
        for (String s : samples) {
            check(VerboseErrorDetector.getVerboseErrorMatch(s) != null, "real error no longer matches: " + s);
        }
        check(VerboseErrorDetector.getVerboseErrorMatch("<html>ok</html>") == null, "plain page matched");
    }

    private static void adversarialBodiesAreFast() {
        String[][] cases = {
                {"ruby frame", repeat("a", ONE_MB)},
                {"v8 frame", repeat("at ", ONE_MB)},
                {"mysql", repeat("SQL syntax ", ONE_MB)},
                {"sqlite near", repeat("near \"", ONE_MB)},
                {"odbc driver", repeat("ODBC Driver[", ONE_MB)},
                {"org.apache", repeat("org.apache.", ONE_MB)},
                {"org.springframework", repeat("at org.springframework.", ONE_MB)},
                {"jjwt", repeat("jjwt.", ONE_MB)},
                {"caused by", "Caused by: " + repeat("a.", ONE_MB)},
                {"python file", repeat("File \"", ONE_MB)},
                {"php fatal", repeat("Fatal error:", ONE_MB)},
        };
        for (String[] c : cases) {
            long start = System.nanoTime();
            VerboseErrorDetector.getVerboseErrorMatch(c[1]);
            long ms = (System.nanoTime() - start) / 1_000_000;
            System.out.println("  " + c[0] + ": " + ms + "ms");
            check(ms < 500, c[0] + " took " + ms + "ms on a 1 MB adversarial body (ReDoS)");
        }
    }

    private static String repeat(String unit, int length) {
        StringBuilder sb = new StringBuilder(length + unit.length());
        while (sb.length() < length) sb.append(unit);
        return sb.toString();
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
