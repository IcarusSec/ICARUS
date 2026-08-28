package icarus.modules.ast.mutators;

import icarus.modules.ast.*;

import java.util.ArrayList;
import java.util.List;

public class RawByteBoundaryMutator extends BaseAstVisitor {
    
    private final String payload;
    private final List<AstMutationResult> mutatedRoots;
    private final OffensiveAstRoot baseRoot;
    
    public RawByteBoundaryMutator(OffensiveAstRoot baseRoot, String payload) {
        this.baseRoot = baseRoot;
        this.payload = payload;
        this.mutatedRoots = new ArrayList<>();
    }
    
    public List<AstMutationResult> getMutatedRoots() {
        return mutatedRoots;
    }
    
    @Override
    public void visit(AstLeaf node, String path) {
        // Target string leaves for raw byte boundary breaking
        if (node.isString()) {
            OffensiveAstRoot clone = baseRoot.deepCopy();
            AstNode targetNode = findNodeByOffsets(clone.getRootNode(), node.getStartOffset(), node.getEndOffset());
            
            if (targetNode instanceof AstLeaf) {
                // Instead of replacing the value, we set a specific taint marker
                // The AstSerializer will detect this marker and inject the payload directly into the raw bytes,
                // bypass JSON encoding and breaking the structure (e.g. injecting unescaped quotes or brackets)
                targetNode.setTaintMarker("RAW_BYTE_INJECTION:" + payload);
                mutatedRoots.add(new AstMutationResult(clone, path, "RAW_BYTE_INJECTION", icarus.core.I18n.t("module.pv.spec.desc.raw_byte_injection"), icarus.core.Category.BOUNDARY, payload));
            }
        }
    }
    
    private AstNode findNodeByOffsets(AstNode current, int start, int end) {
        if (current == null) return null;
        if (current.getStartOffset() == start && current.getEndOffset() == end) {
            return current;
        }
        
        if (current instanceof AstObject) {
            for (AstProperty prop : ((AstObject) current).getProperties()) {
                AstNode found = findNodeByOffsets(prop, start, end);
                if (found != null) return found;
            }
        } else if (current instanceof AstArray) {
            for (AstNode elem : ((AstArray) current).getElements()) {
                AstNode found = findNodeByOffsets(elem, start, end);
                if (found != null) return found;
            }
        } else if (current instanceof AstProperty) {
            return findNodeByOffsets(((AstProperty) current).getValue(), start, end);
        }
        return null;
    }
}
