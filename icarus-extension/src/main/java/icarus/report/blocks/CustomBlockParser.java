package icarus.report.blocks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import icarus.report.model.SectionNode;
import java.util.Locale;
import java.util.Map;

/**
 * Reads custom blocks out of a schemaVersion 1 ReportProfile.
 * Block details live in string params, so profiles written before custom
 * blocks still deserialize, and a block this build does not know is skipped
 * instead of failing the import.
 */
public final class CustomBlockParser {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private CustomBlockParser() {}

    public static boolean isCustomBlock(SectionNode node) {
        if (node == null) return false;
        if (node.rendererKey() != null && "CUSTOM_BLOCK".equalsIgnoreCase(node.rendererKey())) return true;
        return node.id() != null && node.id().toLowerCase(Locale.ROOT).startsWith("block:");
    }

    public static CustomBlock parse(SectionNode node) {
        Map<String, String> params = node == null || node.params() == null ? Map.of() : node.params();
        String title = params.getOrDefault("title", "");
        String rawType = params.getOrDefault("blockType", "");
        String specText = params.get("spec");
        boolean invalid = false;
        JsonNode spec = null;
        if (specText != null && !specText.isBlank()) {
            try {
                spec = MAPPER.readTree(specText);
                if (spec != null && !spec.isObject()) {
                    spec = null;
                    invalid = true;
                }
            } catch (Exception ex) {
                invalid = true;
            }
        }
        if ((rawType == null || rawType.isBlank()) && spec != null && spec.hasNonNull("type")) {
            rawType = spec.get("type").asText("");
        }
        if (title.isBlank() && spec != null && spec.hasNonNull("title")) {
            title = spec.get("title").asText("");
        }
        String type = cleanType(rawType);
        boolean unsupported = !CustomBlock.KNOWN_TYPES.contains(type);
        return new CustomBlock(type, title, spec, invalid, unsupported);
    }

    static String cleanType(String raw) {
        String type = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (type.matches("[a-z][a-z0-9_-]{0,30}")) return type;
        return "unknown";
    }
}
