package icarus.modules.ast;

import java.util.ArrayList;
import java.util.List;

public class AstArray extends AstNode {
    private List<AstNode> elements;

    public AstArray(int startOffset, int endOffset) {
        super(startOffset, endOffset);
        this.elements = new ArrayList<>();
    }

    public AstArray(int startOffset, int endOffset, List<AstNode> elements) {
        super(startOffset, endOffset);
        this.elements = new ArrayList<>(elements);
    }

    public List<AstNode> getElements() { return elements; }

    @Override
    public AstNode deepCopy() {
        List<AstNode> copyElements = new ArrayList<>();
        for (AstNode node : elements) {
            copyElements.add(node.deepCopy());
        }
        AstArray copy = new AstArray(getStartOffset(), getEndOffset(), copyElements);
        copy.setTaintMarker(getTaintMarker());
        return copy;
    }

    @Override
    public void accept(icarus.modules.ast.mutators.AstVisitor visitor, String path) {
        visitor.visit(this, path);
    }
}
