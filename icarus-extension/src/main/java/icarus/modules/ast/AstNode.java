package icarus.modules.ast;

public abstract class AstNode {
    private int startOffset;
    private int endOffset;
    private String taintMarker;

    public AstNode(int startOffset, int endOffset) {
        this.startOffset = startOffset;
        this.endOffset = endOffset;
    }

    public int getStartOffset() { return startOffset; }
    public void setStartOffset(int startOffset) { this.startOffset = startOffset; }

    public int getEndOffset() { return endOffset; }
    public void setEndOffset(int endOffset) { this.endOffset = endOffset; }

    public String getTaintMarker() { return taintMarker; }
    public void setTaintMarker(String taintMarker) { this.taintMarker = taintMarker; }

    public abstract AstNode deepCopy();
    public abstract void accept(icarus.modules.ast.mutators.AstVisitor visitor, String path);
}
