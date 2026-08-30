package icarus.report.render;

import java.nio.file.Path;

/**
 * Isolated evidence image view for report renderers.
 */
public record EvidenceView(
    Path imagePath,
    String caption,
    byte[] imageBytes
) {}
