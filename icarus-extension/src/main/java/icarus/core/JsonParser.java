package icarus.core;

import java.util.*;

/**
 * Minimal JSON parser and serializer.
 *
 * Ported from the ParamValidator Bambda script.
 * No external dependencies — Burp Custom Actions can't import libraries,
 * and we keep that constraint for consistency.
 *
 * Produces:
 * <ul>
 *   <li>{@code LinkedHashMap<String, Object>} for objects (preserves key order)</li>
 *   <li>{@code ArrayList<Object>} for arrays</li>
 *   <li>{@code String}, {@code Long}, {@code Double}, {@code Boolean}, or {@code null} for primitives</li>
 * </ul>
 */
public final class JsonParser {

    private JsonParser() {}

    // ── Parse ───────────────────────────────────────────────────

    public static Object parse(String text) {
        int[] pos = {0};
        skipWhitespace(text, pos);
        return parseValue(text, pos);
    }

    private static void skipWhitespace(String s, int[] p) {
        while (p[0] < s.length() && Character.isWhitespace(s.charAt(p[0]))) {
            p[0]++;
        }
    }

    private static Object parseValue(String s, int[] p) {
        skipWhitespace(s, p);
        char c = s.charAt(p[0]);
        if (c == '{')  return parseObject(s, p);
        if (c == '[')  return parseArray(s, p);
        if (c == '"')  return parseString(s, p);
        if (s.startsWith("true", p[0]))  { p[0] += 4; return Boolean.TRUE; }
        if (s.startsWith("false", p[0])) { p[0] += 5; return Boolean.FALSE; }
        if (s.startsWith("null", p[0]))  { p[0] += 4; return null; }
        return parseNumber(s, p);
    }

    private static LinkedHashMap<String, Object> parseObject(String s, int[] p) {
        var map = new LinkedHashMap<String, Object>();
        p[0]++;
        skipWhitespace(s, p);
        if (s.charAt(p[0]) == '}') { p[0]++; return map; }
        while (true) {
            skipWhitespace(s, p);
            String key = parseString(s, p);
            skipWhitespace(s, p);
            p[0]++; // ':'
            Object value = parseValue(s, p);
            map.put(key, value);
            skipWhitespace(s, p);
            char c = s.charAt(p[0]);
            p[0]++;
            if (c == '}') break;
        }
        return map;
    }

    private static ArrayList<Object> parseArray(String s, int[] p) {
        var list = new ArrayList<Object>();
        p[0]++;
        skipWhitespace(s, p);
        if (s.charAt(p[0]) == ']') { p[0]++; return list; }
        while (true) {
            Object value = parseValue(s, p);
            list.add(value);
            skipWhitespace(s, p);
            char c = s.charAt(p[0]);
            p[0]++;
            if (c == ']') break;
        }
        return list;
    }

    private static String parseString(String s, int[] p) {
        var sb = new StringBuilder();
        p[0]++; // opening quote
        while (s.charAt(p[0]) != '"') {
            char c = s.charAt(p[0]);
            if (c == '\\') {
                p[0]++;
                char esc = s.charAt(p[0]);
                switch (esc) {
                    case 'n'  -> sb.append('\n');
                    case 't'  -> sb.append('\t');
                    case 'r'  -> sb.append('\r');
                    case 'b'  -> sb.append('\b');
                    case 'f'  -> sb.append('\f');
                    case '"'  -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/'  -> sb.append('/');
                    case 'u'  -> {
                        String hex = s.substring(p[0] + 1, p[0] + 5);
                        sb.append((char) Integer.parseInt(hex, 16));
                        p[0] += 4;
                    }
                    default   -> sb.append(esc);
                }
            } else {
                sb.append(c);
            }
            p[0]++;
        }
        p[0]++; // closing quote
        return sb.toString();
    }

    private static Object parseNumber(String s, int[] p) {
        int start = p[0];
        while (p[0] < s.length() && "-+.eE0123456789".indexOf(s.charAt(p[0])) >= 0) {
            p[0]++;
        }
        String num = s.substring(start, p[0]);
        if (num.contains(".") || num.contains("e") || num.contains("E")) {
            return Double.parseDouble(num);
        }
        try {
            return Long.parseLong(num);
        } catch (NumberFormatException e) {
            return Double.parseDouble(num);
        }
    }

    // ── Serialize ───────────────────────────────────────────────

    public static String write(Object o) {
        if (o == null) return "null";
        if (o instanceof String str)    return "\"" + escape(str) + "\"";
        if (o instanceof Boolean)       return String.valueOf(o);
        if (o instanceof Long)          return String.valueOf(o);
        if (o instanceof Integer)       return String.valueOf(o);
        if (o instanceof Double)        return String.valueOf(o);

        if (o instanceof Map<?, ?> map) {
            var sb = new StringBuilder("{");
            boolean first = true;
            for (var e : map.entrySet()) {
                if (!first) sb.append(",");
                first = false;
                sb.append("\"").append(escape((String) e.getKey()))
                  .append("\":").append(write(e.getValue()));
            }
            return sb.append("}").toString();
        }

        if (o instanceof List<?> list) {
            var sb = new StringBuilder("[");
            boolean first = true;
            for (var v : list) {
                if (!first) sb.append(",");
                first = false;
                sb.append(write(v));
            }
            return sb.append("]").toString();
        }

        return "\"" + escape(String.valueOf(o)) + "\"";
    }

    // ── Pretty Print ────────────────────────────────────────────

    public static String prettyPrint(Object o, int indentLevel) {
        if (o == null) return "null";
        if (o instanceof String str) return "\"" + escape(str) + "\"";
        if (o instanceof Boolean || o instanceof Long || o instanceof Integer || o instanceof Double) return String.valueOf(o);

        String indent = "  ".repeat(indentLevel);
        String innerIndent = "  ".repeat(indentLevel + 1);

        if (o instanceof java.util.Map<?, ?> map) {
            if (map.isEmpty()) return "{}";
            StringBuilder sb = new StringBuilder("{\n");
            boolean first = true;
            for (var e : map.entrySet()) {
                if (!first) sb.append(",\n");
                first = false;
                sb.append(innerIndent).append("\"").append(escape((String) e.getKey())).append("\": ");
                sb.append(prettyPrint(e.getValue(), indentLevel + 1));
            }
            return sb.append("\n").append(indent).append("}").toString();
        }

        if (o instanceof java.util.List<?> list) {
            if (list.isEmpty()) return "[]";
            StringBuilder sb = new StringBuilder("[\n");
            boolean first = true;
            for (var v : list) {
                if (!first) sb.append(",\n");
                first = false;
                sb.append(innerIndent).append(prettyPrint(v, indentLevel + 1));
            }
            return sb.append("\n").append(indent).append("]").toString();
        }

        return "\"" + escape(String.valueOf(o)) + "\"";
    }

    public static String formatJsonString(String json) {
        try {
            Object parsed = parse(json);
            return prettyPrint(parsed, 0);
        } catch (Exception e) {
            return json; // Fallback to raw if parsing fails
        }
    }
    public static String escape(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    // ── Deep clone ──────────────────────────────────────────────

    /**
     * Deep-copies a parsed JSON tree so mutations don't affect the original.
     * Cheaper than re-parsing from string for small trees.
     */
    @SuppressWarnings("unchecked")
    public static Object deepCopy(Object o) {
        if (o instanceof Map<?, ?> map) {
            var copy = new LinkedHashMap<String, Object>();
            for (var e : map.entrySet()) {
                copy.put((String) e.getKey(), deepCopy(e.getValue()));
            }
            return copy;
        }
        if (o instanceof List<?> list) {
            var copy = new ArrayList<Object>(list.size());
            for (var v : list) {
                copy.add(deepCopy(v));
            }
            return copy;
        }
        return o; // primitives are immutable
    }
}
