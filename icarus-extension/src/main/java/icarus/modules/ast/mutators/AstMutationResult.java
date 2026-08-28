package icarus.modules.ast.mutators;

import icarus.core.Category;
import icarus.modules.ast.OffensiveAstRoot;

public class AstMutationResult {
    public final OffensiveAstRoot root;
    public final String path;
    public final String type;
    public final String description;
    public final Category category;
    public final Object value; // The payload used

    public AstMutationResult(OffensiveAstRoot root, String path, String type, String description, Category category, Object value) {
        this.root = root;
        this.path = path;
        this.type = type;
        this.description = description;
        this.category = category;
        this.value = value;
    }
}
