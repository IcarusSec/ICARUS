package icarus.core;

public record KnowledgeBaseEntry(
    String name,
    String severity,
    String description,
    String impact,
    String recommendation,
    String impactLevel,
    String probLevel,
    String cwe,
    boolean deleted
) {
    public KnowledgeBaseEntry(
            String name,
            String severity,
            String description,
            String impact,
            String recommendation,
            String impactLevel,
            String probLevel,
            String cwe) {
        this(name, severity, description, impact, recommendation, impactLevel, probLevel, cwe, false);
    }
}
