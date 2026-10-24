package icarus.report.blocks;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Set;

/**
 * A custom block stored on a section node ({@code rendererKey} {@code CUSTOM_BLOCK}).
 * {@code spec} is the parsed {@code params.spec} JSON string. Unknown types and
 * unreadable specs are kept so import does not fail; renderers skip them.
 */
public record CustomBlock(String type, String title, JsonNode spec, boolean invalid, boolean unsupported) {
    public static final Set<String> KNOWN_TYPES = Set.of("chart", "table", "drawing", "image", "callout");

    public CustomBlock {
        if (type == null || type.isBlank()) type = "unknown";
        if (title == null) title = "";
    }

    public String text(String field, String fallback) {
        if (spec != null && spec.hasNonNull(field) && spec.get(field).isTextual()) {
            return spec.get(field).asText();
        }
        return fallback;
    }
}
