package icarus.modules.ast;

import java.nio.charset.StandardCharsets;

public class OffensiveAstRoot {
    private AstNode rootNode;
    private byte[] originalBytes;
    
    public OffensiveAstRoot(AstNode rootNode, byte[] originalBytes) {
        this.rootNode = rootNode;
        this.originalBytes = originalBytes;
    }
    
    public AstNode getRootNode() { return rootNode; }
    public byte[] getOriginalBytes() { return originalBytes; }
    
    public OffensiveAstRoot deepCopy() {
        return new OffensiveAstRoot(rootNode.deepCopy(), originalBytes.clone());
    }
}
