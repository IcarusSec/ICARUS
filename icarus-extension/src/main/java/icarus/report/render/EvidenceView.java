package icarus.report.render;

import java.nio.file.Path;

/**
 * Isolated evidence image view for report renderers.
 */
public record EvidenceView(
    Path imagePath,
    String caption,
    byte[] imageBytes
) {
    /**
     * {@code src} for an HTML {@code <img>}: the image embedded as a data URI when its bytes are
     * available (the report is then self-contained — the profile/preview HTML path never copies
     * screenshots next to the file, so a bare filename rendered as a broken image), otherwise the
     * percent-encoded filename (a user-chosen name with spaces, '#' or '%' broke the link).
     * Null when there is neither.
     */
    public String htmlSrc() {
        if (imageBytes != null && imageBytes.length > 0) {
            return "data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(imageBytes);
        }
        if (imagePath == null || imagePath.getFileName() == null) return null;
        return encodeFileName(imagePath.getFileName().toString());
    }

    /** Percent-encodes a bare file name for use as a relative URL (also attribute-safe: no quotes survive). */
    public static String encodeFileName(String name) {
        try {
            return new java.net.URI(null, null, name, null).toASCIIString().replace("'", "%27").replace("\"", "%22");
        } catch (java.net.URISyntaxException e) {
            return java.net.URLEncoder.encode(name, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
        }
    }
}
