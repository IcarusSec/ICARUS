package icarus.modules.ast;

import java.util.ArrayList;
import java.util.List;

public class AstObject extends AstNode {
    private List<AstProperty> properties;

    public AstObject(int startOffset, int endOffset) {
        super(startOffset, endOffset);
        this.properties = new ArrayList<>();
    }

    public AstObject(int startOffset, int endOffset, List<AstProperty> properties) {
        super(startOffset, endOffset);
        this.properties = new ArrayList<>(properties);
    }

    public List<AstProperty> getProperties() { return properties; }

    @Override
    public AstNode deepCopy() {
        List<AstProperty> copyProps = new ArrayList<>();
        for (AstProperty prop : properties) {
            copyProps.add((AstProperty) prop.deepCopy());
        }
        AstObject copy = new AstObject(getStartOffset(), getEndOffset(), copyProps);
        copy.setTaintMarker(getTaintMarker());
        return copy;
    }

    @Override
    public void accept(icarus.modules.ast.mutators.AstVisitor visitor, String path) {
        visitor.visit(this, path);
    }
}
