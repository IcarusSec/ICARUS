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
