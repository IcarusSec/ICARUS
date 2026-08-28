package icarus.modules.ast;

public class AstProperty extends AstNode {
    private String key;
    private AstNode value;

    public AstProperty(int startOffset, int endOffset, String key, AstNode value) {
        super(startOffset, endOffset);
        this.key = key;
        this.value = value;
    }

    public String getKey() { return key; }
    public void setKey(String key) { this.key = key; }

    public AstNode getValue() { return value; }
    public void setValue(AstNode value) { this.value = value; }

    @Override
    public AstNode deepCopy() {
        AstProperty copy = new AstProperty(getStartOffset(), getEndOffset(), key, value != null ? value.deepCopy() : null);
        copy.setTaintMarker(getTaintMarker());
        return copy;
    }

    @Override
    public void accept(icarus.modules.ast.mutators.AstVisitor visitor, String path) {
        visitor.visit(this, path);
    }
}
