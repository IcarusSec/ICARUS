package icarus.modules.ast.mutators;

import icarus.core.Category;
import icarus.modules.ast.*;

import java.util.ArrayList;
import java.util.List;

public class LeafReplacementMutator extends BaseAstVisitor {
    
    private final String payload;
    private final String type;
    private final String description;
    private final Category category;
    private final List<AstMutationResult> mutatedRoots;
    private final OffensiveAstRoot baseRoot;
    
    public LeafReplacementMutator(OffensiveAstRoot baseRoot, String type, String description, Category category, String payload) {
        this.baseRoot = baseRoot;
        this.type = type;
        this.description = description;
        this.category = category;
        this.payload = payload;
        this.mutatedRoots = new ArrayList<>();
    }
    
    public List<AstMutationResult> getMutatedRoots() {
        return mutatedRoots;
    }
    
    @Override
    public void visit(AstLeaf node, String path) {
        OffensiveAstRoot clone = baseRoot.deepCopy();
        AstNode targetNode = findNodeByOffsets(clone.getRootNode(), node.getStartOffset(), node.getEndOffset());
        
        if (targetNode instanceof AstLeaf) {
            replaceNodeInTree(clone.getRootNode(), targetNode, new AstLeaf(-1, -1, payload, true));
            mutatedRoots.add(new AstMutationResult(clone, path, type, description, category, payload));
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
    
    private boolean replaceNodeInTree(AstNode parent, AstNode target, AstNode replacement) {
        if (parent == null) return false;
        
        if (parent instanceof AstObject) {
            for (AstProperty prop : ((AstObject) parent).getProperties()) {
                if (prop.getValue() == target) {
                    prop.setValue(replacement);
                    return true;
                }
                if (replaceNodeInTree(prop.getValue(), target, replacement)) return true;
            }
        } else if (parent instanceof AstArray) {
            List<AstNode> elements = ((AstArray) parent).getElements();
            for (int i = 0; i < elements.size(); i++) {
                if (elements.get(i) == target) {
                    elements.set(i, replacement);
                    return true;
                }
                if (replaceNodeInTree(elements.get(i), target, replacement)) return true;
            }
        }
        return false;
    }
}
