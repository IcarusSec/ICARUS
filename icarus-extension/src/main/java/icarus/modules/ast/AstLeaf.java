package icarus.modules.ast;

public class AstLeaf extends AstNode {
    private Object value; // String, Number, Boolean, or null
    private boolean isString; // If true, indicates a string (meaning it was enclosed in quotes)

    public AstLeaf(int startOffset, int endOffset, Object value, boolean isString) {
        super(startOffset, endOffset);
        this.value = value;
        this.isString = isString;
    }

    public Object getValue() { return value; }
    public void setValue(Object value) { this.value = value; }

    public boolean isString() { return isString; }

    @Override
    public AstNode deepCopy() {
        AstLeaf copy = new AstLeaf(getStartOffset(), getEndOffset(), value, isString);
        copy.setTaintMarker(getTaintMarker());
        return copy;
    }

    @Override
    public void accept(icarus.modules.ast.mutators.AstVisitor visitor, String path) {
        visitor.visit(this, path);
    }
}
