package icarus.modules.ast.mutators;

import icarus.modules.ast.*;

import java.util.ArrayList;
import java.util.List;

public class HppMutator extends BaseAstVisitor {
    
    private final String payload;
    private final List<AstMutationResult> mutatedRoots;
    private final OffensiveAstRoot baseRoot;
    
    public HppMutator(OffensiveAstRoot baseRoot, String payload) {
        this.baseRoot = baseRoot;
        this.payload = payload;
        this.mutatedRoots = new ArrayList<>();
    }
    
    public List<AstMutationResult> getMutatedRoots() {
        return mutatedRoots;
    }
    
    @Override
    public void visit(AstProperty node, String path) {
        // HTTP Parameter Pollution in JSON: duplicate the key
        OffensiveAstRoot clone = baseRoot.deepCopy();
        AstProperty targetProp = (AstProperty) findNodeByOffsets(clone.getRootNode(), node.getStartOffset(), node.getEndOffset());
        
        if (targetProp != null) {
            AstObject parentObj = findParentObject(clone.getRootNode(), targetProp);
            if (parentObj != null) {
                // Clone the property, but change its value to our payload
                AstProperty duplicatedProp = new AstProperty(-1, -1, targetProp.getKey(), new AstLeaf(-1, -1, payload, true));
                duplicatedProp.setTaintMarker("HPP_DUPLICATE_KEY");
                
                // Add the duplicated property right after the original one
                int index = parentObj.getProperties().indexOf(targetProp);
                parentObj.getProperties().add(index + 1, duplicatedProp);
                
                mutatedRoots.add(new AstMutationResult(clone, path, "HPP_DUPLICATE_KEY", icarus.core.I18n.t("module.pv.spec.desc.hpp_duplicate_key"), icarus.core.Category.STRUCTURAL, payload));
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
    
    private AstObject findParentObject(AstNode current, AstProperty target) {
        if (current == null) return null;
        
        if (current instanceof AstObject) {
            AstObject obj = (AstObject) current;
            if (obj.getProperties().contains(target)) return obj;
            
            for (AstProperty prop : obj.getProperties()) {
                AstObject found = findParentObject(prop.getValue(), target);
                if (found != null) return found;
            }
        } else if (current instanceof AstArray) {
            for (AstNode elem : ((AstArray) current).getElements()) {
                AstObject found = findParentObject(elem, target);
                if (found != null) return found;
            }
        }
        return null;
    }
}
