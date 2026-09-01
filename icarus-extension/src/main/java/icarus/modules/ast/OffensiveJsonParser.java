package icarus.modules.ast;

import java.nio.charset.StandardCharsets;

public class OffensiveJsonParser {

    /**
     * Hard cap on object/array nesting — see {@link icarus.core.JsonParser#MAX_DEPTH}. This
     * recursive-descent parser runs on attacker-influenced HTTP bodies; without the bound,
     * deeply nested input ({@code [[[[…]]]]}) is a StackOverflowError instead of a parse failure.
     */
    static final int MAX_DEPTH = 512;

    public static OffensiveAstRoot parse(byte[] payload) {
        String text = new String(payload, StandardCharsets.UTF_8);
        int[] pos = {0};
        skipWhitespace(text, pos);
        AstNode root = parseValue(text, pos, 0);
        return new OffensiveAstRoot(root, payload);
    }

    private static void skipWhitespace(String s, int[] p) {
        while (p[0] < s.length() && Character.isWhitespace(s.charAt(p[0]))) {
            p[0]++;
        }
    }

    private static AstNode parseValue(String s, int[] p, int depth) {
        if (depth > MAX_DEPTH) {
            throw new IllegalArgumentException("JSON nesting too deep (> " + MAX_DEPTH + ")");
        }
        skipWhitespace(s, p);
        if (p[0] >= s.length()) return null;

        char c = s.charAt(p[0]);
        if (c == '{') return parseObject(s, p, depth);
        if (c == '[') return parseArray(s, p, depth);
        if (c == '"') return parseString(s, p);
        if (s.startsWith("true", p[0])) { 
            int start = p[0];
            p[0] += 4; 
            return new AstLeaf(start, p[0], Boolean.TRUE, false); 
        }
        if (s.startsWith("false", p[0])) { 
            int start = p[0];
            p[0] += 5; 
            return new AstLeaf(start, p[0], Boolean.FALSE, false); 
        }
        if (s.startsWith("null", p[0])) { 
            int start = p[0];
            p[0] += 4; 
            return new AstLeaf(start, p[0], null, false); 
        }
        return parseNumber(s, p);
    }
    
    private static AstObject parseObject(String s, int[] p, int depth) {
        int startOffset = p[0];
        AstObject obj = new AstObject(startOffset, -1);
        p[0]++; // '{'
        skipWhitespace(s, p);
        
        if (p[0] < s.length() && s.charAt(p[0]) == '}') { 
            p[0]++; 
            obj.setEndOffset(p[0]);
            return obj; 
        }
        
        while (p[0] < s.length()) {
            skipWhitespace(s, p);
            
            int propStart = p[0];
            AstLeaf keyLeaf = parseString(s, p);
            String key = (String) keyLeaf.getValue();
            
            skipWhitespace(s, p);
            if (p[0] < s.length() && s.charAt(p[0]) == ':') p[0]++;
            
            AstNode value = parseValue(s, p, depth + 1);

            AstProperty prop = new AstProperty(propStart, value != null ? value.getEndOffset() : p[0], key, value);
            obj.getProperties().add(prop);
            
            skipWhitespace(s, p);
            if (p[0] >= s.length()) break;
            
            char c = s.charAt(p[0]);
            p[0]++;
            if (c == '}') break;
        }
        
        obj.setEndOffset(p[0]);
        return obj;
    }
    
    private static AstArray parseArray(String s, int[] p, int depth) {
        int startOffset = p[0];
        AstArray arr = new AstArray(startOffset, -1);
        p[0]++; // '['
        skipWhitespace(s, p);
        
        if (p[0] < s.length() && s.charAt(p[0]) == ']') { 
            p[0]++; 
            arr.setEndOffset(p[0]);
            return arr; 
        }
        
        while (p[0] < s.length()) {
            AstNode value = parseValue(s, p, depth + 1);
            if (value != null) {
                arr.getElements().add(value);
            }
            
            skipWhitespace(s, p);
            if (p[0] >= s.length()) break;
            
            char c = s.charAt(p[0]);
            p[0]++;
            if (c == ']') break;
        }
        
        arr.setEndOffset(p[0]);
        return arr;
    }
    
    private static AstLeaf parseString(String s, int[] p) {
        int startOffset = p[0];
        StringBuilder sb = new StringBuilder();
        p[0]++; // opening quote
        
        while (p[0] < s.length() && s.charAt(p[0]) != '"') {
            char c = s.charAt(p[0]);
            if (c == '\\') {
                p[0]++;
                if (p[0] < s.length()) {
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
                            if (p[0] + 4 < s.length()) {
                                String hex = s.substring(p[0] + 1, p[0] + 5);
                                sb.append((char) Integer.parseInt(hex, 16));
                                p[0] += 4;
                            }
                        }
                        default   -> sb.append(esc);
                    }
                }
            } else {
                sb.append(c);
            }
            p[0]++;
        }
        
        if (p[0] < s.length()) p[0]++; // closing quote
        return new AstLeaf(startOffset, p[0], sb.toString(), true);
    }
    
    private static AstLeaf parseNumber(String s, int[] p) {
        int startOffset = p[0];
        while (p[0] < s.length() && "-+.eE0123456789".indexOf(s.charAt(p[0])) >= 0) {
            p[0]++;
        }
        if (p[0] == startOffset) {
            if (p[0] < s.length()) p[0]++;
            return null;
        }
        String numStr = s.substring(startOffset, p[0]);
        return new AstLeaf(startOffset, p[0], numStr, false);
    }

    /** `java -cp build_manual/libs/icarus-<version>.jar icarus.modules.ast.OffensiveJsonParser`
     *  — parse a small object and prove deeply nested input fails cleanly. */
    public static void main(String[] args) {
        java.nio.charset.Charset u8 = StandardCharsets.UTF_8;
        assert parse("{\"a\":[1,2,{\"b\":\"c\"}]}".getBytes(u8)).getRootNode() != null
                : "basic object must parse to a non-null root";

        String deep = "[".repeat(MAX_DEPTH + 50) + "]".repeat(MAX_DEPTH + 50);
        boolean threw = false;
        try { parse(deep.getBytes(u8)); }
        catch (IllegalArgumentException e) { threw = true; }
        catch (StackOverflowError e) { threw = false; }
        assert threw : "deeply nested JSON must throw IllegalArgumentException, not overflow the stack";

        System.out.println("OffensiveJsonParser self-check passed (run with -ea to enforce).");
    }
}
