package icarus.core;

import java.util.List;
import java.util.regex.Pattern;

public class VerboseErrorDetector {

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
        Pattern.compile("(?i)org\\.springframework\\.\\w+"),
        Pattern.compile("(?i)org\\.apache\\.\\w+"),

        // Python
        Pattern.compile("(?i)Traceback \\(most recent call last\\):"),
        Pattern.compile("(?i)File \"[^\"]+\", line \\d+, in"),
        Pattern.compile("(?i)werkzeug\\.exceptions\\."),

        // PHP
        Pattern.compile("(?i)Fatal error: Uncaught"),
        Pattern.compile("(?i)Fatal error:.*on line \\d+"),
        Pattern.compile("(?i)Warning:.*on line \\d+"),
        Pattern.compile("(?i)Notice:.*on line \\d+"),
        Pattern.compile("(?i)PHP (?:Warning|Notice|Parse error):"),
        Pattern.compile("(?i)\\bStack trace:\\b"),
        Pattern.compile("(?i)\\bstack ?trace\\b"),

        // Ruby
        Pattern.compile("(?i)\\w+\\.rb:\\d+:in"),
        Pattern.compile("(?i)NoMethodError:"),

        // Node.js / JavaScript
        Pattern.compile("(?i)TypeError:.*"),
        Pattern.compile("(?i)ReferenceError:.*"),
        Pattern.compile("(?i)SyntaxError:.*"),
        Pattern.compile("(?i)UnhandledPromiseRejection"),
        Pattern.compile("(?i)Caused by:"),
        Pattern.compile("(?i)at .* \\((?:.*/)*.*:\\d+:\\d+\\)"), // V8 stacktrace

        // C# / ASP.NET
        Pattern.compile("(?i)System\\.\\w+Exception"),
        Pattern.compile("(?i)Server Error in '/' Application"),
        Pattern.compile("(?i)at System\\.Web\\.[a-zA-Z\\.]+"),

        // JWT / auth libraries
        Pattern.compile("(?i)\\bJsonWebTokenError\\b"),
        Pattern.compile("(?i)\\bjsonwebtoken\\b"),
        Pattern.compile("(?i)\\bjjwt\\b"),
        Pattern.compile("(?i)\\bjose\\b"),

        // General / Web Servers
        Pattern.compile("(?i)Whitelabel Error Page")
    );

    /** Returns the matched string for reporting, or null if no match. */
    public static String getVerboseErrorMatch(String body) {
        if (body == null || body.isBlank()) return null;

        for (Pattern p : DB_ERROR_PATTERNS) {
            var m = p.matcher(body);
            if (m.find()) return "Database Error: " + m.group();
        }
        for (Pattern p : FRAMEWORK_AND_LANG_PATTERNS) {
            var m = p.matcher(body);
            if (m.find()) return "Framework/Language Error: " + m.group();
        }
        return null;
    }
}
