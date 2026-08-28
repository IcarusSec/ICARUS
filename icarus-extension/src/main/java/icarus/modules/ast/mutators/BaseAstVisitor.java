package icarus.modules.ast.mutators;

import icarus.modules.ast.*;
import java.util.ArrayList;
import java.util.List;

public abstract class BaseAstVisitor implements AstVisitor {

    public void traverse(AstNode node) {
        traverse(node, "$");
    }

    public void traverse(AstNode node, String currentPath) {
        if (node == null) return;
        
        node.accept(this, currentPath);
        
        if (node instanceof AstObject) {
            for (AstProperty prop : ((AstObject) node).getProperties()) {
                String nextPath = currentPath.equals("$") ? "$." + prop.getKey() : currentPath + "." + prop.getKey();
                traverse(prop, nextPath);
            }
        } else if (node instanceof AstArray) {
            List<AstNode> elements = ((AstArray) node).getElements();
            for (int i = 0; i < elements.size(); i++) {
                traverse(elements.get(i), currentPath + "[" + i + "]");
            }
        } else if (node instanceof AstProperty) {
            traverse(((AstProperty) node).getValue(), currentPath);
        }
    }

    @Override
    public void visit(AstNode node, String path) {}

    @Override
    public void visit(AstObject node, String path) {}

    @Override
    public void visit(AstArray node, String path) {}

    @Override
    public void visit(AstProperty node, String path) {}

    @Override
    public void visit(AstLeaf node, String path) {}
}
