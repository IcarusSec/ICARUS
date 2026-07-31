package icarus.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Walks and addresses a parsed {@link JsonParser} tree by JSONPath-like segment lists
 * (e.g. {@code ["auth", "access_token"]} <-> {@code "$.auth.access_token"}).
 *
 * Extracted from ParamValidatorModule's mutation engine so AutoAuthModule can reuse the
 * same path-collection/addressing logic instead of reimplementing a JSON tree walker.
 */
public final class JsonPaths {

    private JsonPaths() {}

    public static List<List<Object>> collect(Object root) {
        List<List<Object>> out = new ArrayList<>();
        walk(root, new ArrayList<>(), out);
        return out;
    }

    private static void walk(Object node, List<Object> current, List<List<Object>> out) {
        if (node instanceof Map<?, ?> map) {
            for (Object key : map.keySet()) {
                List<Object> childPath = new ArrayList<>(current);
                childPath.add(key);
                out.add(childPath);
                Object value = map.get(key);
                if (value instanceof Map || value instanceof List) walk(value, childPath, out);
            }
        } else if (node instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) {
                List<Object> childPath = new ArrayList<>(current);
                childPath.add(i);
                out.add(childPath);
                Object value = list.get(i);
                if (value instanceof Map || value instanceof List) walk(value, childPath, out);
            }
        }
    }

    public static Object getAt(Object root, List<Object> path) {
        Object current = root;
        for (Object key : path) {
            if (current instanceof Map<?, ?> m && key instanceof String s) current = m.get(s);
            else if (current instanceof List<?> l && key instanceof Integer idx) current = l.get(idx);
            else return null;
        }
        return current;
    }

    /**
     * Replaces (or removes) the value at {@code path} in place.
     *
     * @param remove if true, {@code value} is ignored and the key/index is removed instead
     * @return true if the mutation was applied
     */
    @SuppressWarnings("unchecked")
    public static boolean applyAt(Object root, List<Object> path, Object value, boolean remove) {
        if (path.isEmpty()) return false;
        Object parent = root;
        for (int i = 0; i < path.size() - 1; i++) {
            Object key = path.get(i);
            if (parent instanceof Map<?, ?> m && key instanceof String s) parent = m.get(s);
            else if (parent instanceof List<?> l && key instanceof Integer idx) parent = l.get(idx);
            else return false;
        }
        Object lastKey = path.get(path.size() - 1);
        if (remove) {
            if (parent instanceof Map<?, ?> m && lastKey instanceof String s) {
                ((Map<Object, Object>) m).remove(s);
                return true;
            }
            return false;
        }
        if (parent instanceof Map<?, ?> m && lastKey instanceof String s) {
            ((Map<Object, Object>) m).put(s, value);
            return true;
        } else if (parent instanceof List<?> l && lastKey instanceof Integer idx) {
            ((List<Object>) l).set(idx, value);
            return true;
        }
        return false;
    }

    /** Convenience wrapper over {@link #applyAt} for a plain value replacement (no removal). */
    public static boolean setAt(Object root, List<Object> path, Object value) {
        return applyAt(root, path, value, false);
    }

    public static String pathToString(List<Object> path) {
        StringBuilder sb = new StringBuilder("$");
        for (Object p : path) {
            if (p instanceof String s) sb.append(".").append(s);
            else sb.append("[").append(p).append("]");
        }
        return sb.toString();
    }

    /** Inverse of {@link #pathToString}: parses {@code "$.a.b[0].c"} back into segments. */
    public static List<Object> parsePath(String pathStr) {
        List<Object> path = new ArrayList<>();
        if (pathStr == null || !pathStr.startsWith("$")) return path;
        int i = 1;
        while (i < pathStr.length()) {
            char c = pathStr.charAt(i);
            if (c == '.') {
                int start = ++i;
                while (i < pathStr.length() && pathStr.charAt(i) != '.' && pathStr.charAt(i) != '[') i++;
                path.add(pathStr.substring(start, i));
            } else if (c == '[') {
                int start = ++i;
                while (i < pathStr.length() && pathStr.charAt(i) != ']') i++;
                path.add(Integer.parseInt(pathStr.substring(start, i)));
                i++; // skip ']'
            } else {
                i++;
            }
        }
        return path;
    }

    /**
     * Finds every leaf scalar in the tree whose value, re-encoded the way it would appear
     * literally in raw JSON text, equals {@code rawText}. Comparing against the re-encoded
     * form (rather than decoding {@code rawText}) matters because {@code rawText} is expected
     * to come straight from a user's raw-text selection in Burp's message editor — e.g. a
     * string value containing a quote or backslash appears escaped there, so the leaf value
     * must be escaped the same way before comparing, not decoded.
     */
    public static List<List<Object>> findPathsByValue(Object root, String rawText) {
        List<List<Object>> matches = new ArrayList<>();
        for (List<Object> path : collect(root)) {
            Object leaf = getAt(root, path);
            if (leaf instanceof Map || leaf instanceof List) continue;
            String candidate = leaf instanceof String s ? JsonParser.escape(s) : String.valueOf(leaf);
            if (candidate.equals(rawText)) matches.add(path);
        }
        return matches;
    }
}
