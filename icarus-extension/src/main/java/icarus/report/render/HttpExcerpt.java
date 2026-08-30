package icarus.report.render;

/**
 * Truncated/sanitized HTTP request or response excerpt.
 */
public record HttpExcerpt(
    String text,
    boolean truncated,
    int originalBytes
) {
    public static HttpExcerpt empty() {
        return new HttpExcerpt("", false, 0);
    }
}
