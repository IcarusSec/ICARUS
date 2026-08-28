package icarus.modules.ast;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public class AstSerializer {
    
    public static class SerializedResult {
        public final byte[] payload;
        public final String taintMarker;
        
        public SerializedResult(byte[] payload, String taintMarker) {
            this.payload = payload;
            this.taintMarker = taintMarker;
        }
    }

    public static SerializedResult serialize(OffensiveAstRoot astRoot) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        String[] foundMarker = new String[1];
        serializeNode(astRoot.getRootNode(), out, foundMarker);
        return new SerializedResult(out.toByteArray(), foundMarker[0]);
    }
    
    private static void serializeNode(AstNode node, ByteArrayOutputStream out, String[] foundMarker) throws IOException {
        if (node == null) {
            out.write("null".getBytes(StandardCharsets.UTF_8));
            return;
        }
        
        if (node.getTaintMarker() != null) {
            foundMarker[0] = node.getTaintMarker();
            if (node.getTaintMarker().startsWith("RAW_BYTE_INJECTION:")) {
                String rawPayload = node.getTaintMarker().substring("RAW_BYTE_INJECTION:".length());
                out.write(rawPayload.getBytes(StandardCharsets.UTF_8));
                return;
            }
        }
        
        if (node instanceof AstObject) {
            out.write("{".getBytes(StandardCharsets.UTF_8));
            boolean first = true;
            for (AstProperty prop : ((AstObject) node).getProperties()) {
                if (!first) out.write(",".getBytes(StandardCharsets.UTF_8));
                first = false;
                serializeNode(prop, out, foundMarker);
            }
            out.write("}".getBytes(StandardCharsets.UTF_8));
        } else if (node instanceof AstArray) {
            out.write("[".getBytes(StandardCharsets.UTF_8));
            boolean first = true;
            for (AstNode elem : ((AstArray) node).getElements()) {
                if (!first) out.write(",".getBytes(StandardCharsets.UTF_8));
                first = false;
                serializeNode(elem, out, foundMarker);
            }
            out.write("]".getBytes(StandardCharsets.UTF_8));
        } else if (node instanceof AstProperty) {
            AstProperty prop = (AstProperty) node;
            // Keys are always serialized properly, unless specifically tainted for RAW bypass
            out.write("\"".getBytes(StandardCharsets.UTF_8));
            out.write(escapeJson(prop.getKey()).getBytes(StandardCharsets.UTF_8));
            out.write("\":".getBytes(StandardCharsets.UTF_8));
            serializeNode(prop.getValue(), out, foundMarker);
        } else if (node instanceof AstLeaf) {
            AstLeaf leaf = (AstLeaf) node;
            if (leaf.getValue() == null) {
                out.write("null".getBytes(StandardCharsets.UTF_8));
            } else if (leaf.isString()) {
                out.write("\"".getBytes(StandardCharsets.UTF_8));
                out.write(escapeJson((String) leaf.getValue()).getBytes(StandardCharsets.UTF_8));
                out.write("\"".getBytes(StandardCharsets.UTF_8));
            } else {
                out.write(leaf.getValue().toString().getBytes(StandardCharsets.UTF_8));
            }
        }
    }
    
    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
