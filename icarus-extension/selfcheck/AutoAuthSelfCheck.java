import burp.api.montoya.core.Range;
import icarus.autoauth.AutoAuthModule;
import icarus.core.JsonParser;
import icarus.core.JsonPaths;

import java.lang.reflect.Method;
import java.util.List;

/**
 * Framework-free self-check for the two parser-like pieces of AutoAuth's logic that are
 * easy to get subtly wrong: JsonPaths string<->segment round-tripping / escape-aware value
 * matching, and AutoAuthModule's header-line/prefix detection from raw selection offsets.
 *
 * Not part of the shipped build (lives outside src/main/java, build.sh never sees it).
 * Compile+run manually against the same classpath used by build.sh:
 *   javac -cp "../build_manual/classes:../libs/montoya-api-*.jar" -d /tmp/selfcheck AutoAuthSelfCheck.java
 *   java  -ea -cp "/tmp/selfcheck:../build_manual/classes:../libs/montoya-api-*.jar" AutoAuthSelfCheck
 */
public final class AutoAuthSelfCheck {

    public static void main(String[] args) throws Exception {
        jsonPathsRoundTrip();
        jsonPathsEscapeAwareMatch();
        headerTargetDetection();
        System.out.println("AutoAuthSelfCheck: all checks passed.");
    }

    private static void jsonPathsRoundTrip() {
        List<Object> path = List.of("auth", "tokens", 0, "access_token");
        String str = JsonPaths.pathToString(path);
        assertEquals("$.auth.tokens[0].access_token", str, "pathToString");
        assertEquals(path, JsonPaths.parsePath(str), "parsePath round-trip");
    }

    private static void jsonPathsEscapeAwareMatch() {
        // Value contains a character ('/') that some JSON serializers escape as \/, plus a
        // literal backslash+quote combo that MUST be escaped. The raw text as it would
        // appear (and be highlighted) in Burp's editor is the escaped form.
        String rawValue = "abc\"def\\ghi";
        String body = "{\"auth\":{\"access_token\":\"" + JsonParser.escape(rawValue) + "\"}}";
        Object root = JsonParser.parse(body);

        // Simulate what the user would actually highlight in the raw response text: the
        // escaped form, exactly as JsonParser.write() would have produced it.
        String highlightedAsShownInEditor = JsonParser.escape(rawValue);

        List<List<Object>> matches = JsonPaths.findPathsByValue(root, highlightedAsShownInEditor);
        assertEquals(1, matches.size(), "escape-aware match count");
        assertEquals("$.auth.access_token", JsonPaths.pathToString(matches.get(0)), "escape-aware match path");

        // Sanity: comparing against the *decoded* value (what a naive implementation might
        // do) must NOT match, proving the test actually exercises the escaping behavior.
        List<List<Object>> wrongMatches = JsonPaths.findPathsByValue(root, rawValue);
        assertEquals(0, wrongMatches.size(), "decoded value must not match raw escaped text");
    }

    private static void headerTargetDetection() throws Exception {
        String raw = "POST /login HTTP/1.1\r\n"
                + "Host: example.com\r\n"
                + "Authorization: Bearer OLDTOKEN123\r\n"
                + "Content-Type: application/json\r\n"
                + "\r\n"
                + "{}";
        int selStart = raw.indexOf("OLDTOKEN123");
        int selEnd = selStart + "OLDTOKEN123".length();

        // buildHeaderTarget is a pure instance method (touches no fields), so a minimal
        // real instance — no MontoyaApi needed since an empty ModuleConfig short-circuits
        // the constructor's loadSession() before it would ever touch api — is enough to
        // invoke it via reflection.
        AutoAuthModule instance = new AutoAuthModule(null, new icarus.core.ModuleConfig());
        Method m = AutoAuthModule.class.getDeclaredMethod("buildHeaderTarget", String.class, Range.class, String.class);
        m.setAccessible(true);

        Object target = m.invoke(instance, raw, fakeRange(selStart, selEnd), "example.com");
        assertNotNull(target, "header target");
        assertEquals("Authorization", invokeRecordAccessor(target, "headerName"), "header name");
        assertEquals("Bearer ", invokeRecordAccessor(target, "headerPrefix"), "header prefix");
        assertEquals("example.com", invokeRecordAccessor(target, "host"), "header target host");

        // No prefix case: highlight starts exactly at the value.
        int noPrefixStart = raw.indexOf("Bearer ");
        Object noPrefixTarget = m.invoke(instance, raw, fakeRange(noPrefixStart, selEnd), "example.com");
        assertEquals("", invokeRecordAccessor(noPrefixTarget, "headerPrefix"), "no-prefix case");
    }

    private static Range fakeRange(int start, int end) {
        return new Range() {
            public int startIndexInclusive() { return start; }
            public int endIndexExclusive() { return end; }
            public boolean contains(int index) { return index >= start && index < end; }
        };
    }

    private static Object invokeRecordAccessor(Object record, String name) throws Exception {
        Method accessor = record.getClass().getDeclaredMethod(name);
        accessor.setAccessible(true);
        return accessor.invoke(record);
    }

    private static void assertEquals(Object expected, Object actual, String label) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError(label + ": expected <" + expected + "> but got <" + actual + ">");
        }
    }

    private static void assertNotNull(Object o, String label) {
        if (o == null) throw new AssertionError(label + ": expected non-null");
    }
}
