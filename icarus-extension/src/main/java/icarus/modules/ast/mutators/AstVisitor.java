package icarus.modules.ast.mutators;

import icarus.modules.ast.*;

public interface AstVisitor {
    void visit(AstNode node, String path);
    void visit(AstObject node, String path);
    void visit(AstArray node, String path);
    void visit(AstProperty node, String path);
    void visit(AstLeaf node, String path);
}
