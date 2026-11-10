package icarus.report.canvas;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import icarus.core.Severity;
import icarus.report.model.BrandingConfig;
import icarus.report.model.SectionNode;
import icarus.report.render.ReportData;
import icarus.report.render.ReportRenderContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads free-form pages stored as {@code CANVAS_PAGE} sections.
 * {@code params.spec} is a JSON string, so a schemaVersion 1 profile that
 * has no canvas pages still deserializes exactly as before.
 */
public final class CanvasPages {
    public static final float MM = 72f / 25.4f;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern TOKEN = Pattern.compile("\\{\\{\\s*([a-z0-9_]+)\\s*\\}\\}", Pattern.CASE_INSENSITIVE);

    private CanvasPages() {}

    public static boolean isCanvas(SectionNode node) {
        if (node == null) return false;
        if (node.rendererKey() != null && "CANVAS_PAGE".equalsIgnoreCase(node.rendererKey())) return true;
        return node.id() != null && node.id().toLowerCase(Locale.ROOT).startsWith("page:");
    }

    public static boolean hasCanvas(icarus.report.model.ReportProfile profile) {
        if (profile == null || profile.sections() == null) return false;
        for (SectionNode node : profile.sections().enabledInOrder()) {
            if (isCanvas(node)) return true;
        }
        return false;
    }

    /** Parsed page. {@code spec} is null when the stored text is not JSON. */
    public record Page(String name, String role, String background, JsonNode spec, boolean invalid) {}

    public static Page parse(SectionNode node) {
        Map<String, String> params = node == null || node.params() == null ? Map.of() : node.params();
        String name = params.getOrDefault("title", "Page");
        String role = params.getOrDefault("role", "interior");
        String raw = params.get("spec");
        if (raw == null || raw.isBlank()) {
            return new Page(name, role, "#FFFFFF", null, false);
        }
        try {
            JsonNode spec = MAPPER.readTree(raw);
            if (spec == null || !spec.isObject()) return new Page(name, role, "#FFFFFF", null, true);
            String background = text(spec, "background", "#FFFFFF");
            return new Page(name, role, background, spec, false);
        } catch (Exception ex) {
            return new Page(name, role, "#FFFFFF", null, true);
        }
    }

    public static List<JsonNode> elements(Page page) {
        List<JsonNode> list = new ArrayList<>();
        if (page == null || page.spec() == null || !page.spec().path("elements").isArray()) return list;
        page.spec().path("elements").forEach(list::add);
        list.sort((a, b) -> Integer.compare(a.path("z").asInt(0), b.path("z").asInt(0)));
        return list;
    }

    public static Map<String, String> variables(ReportRenderContext ctx, int pageNumber, int pageCount) {
        Map<String, String> vars = new LinkedHashMap<>();
        ReportData data = ctx == null ? null : ctx.data();
        BrandingConfig branding = ctx == null || ctx.profile() == null ? null : ctx.profile().branding();
        if (data != null && data.variables() != null) vars.putAll(data.variables());
        if (data != null) {
            put(vars, "report_title", data.reportTitle());
            put(vars, "project", data.projectName());
            put(vars, "finding_count", String.valueOf(data.findings().size()));
            for (Severity severity : Severity.values()) {
                vars.put("count_" + severity.name().toLowerCase(Locale.ROOT), String.valueOf(data.getCount(severity)));
            }
        }
        if (branding != null) {
            put(vars, "tester", first(vars.get("author"), branding.author()));
            put(vars, "author", first(vars.get("author"), branding.author()));
            put(vars, "client", first(vars.get("client"), branding.requester()));
            put(vars, "classification", first(vars.get("classification"), branding.classification()));
            put(vars, "date", first(vars.get("date"), branding.assessmentPeriod()));
            if (vars.get("report_title") == null || vars.get("report_title").isBlank()) {
                put(vars, "report_title", branding.documentTitle());
            }
        }
        if (!vars.containsKey("version") || vars.get("version") == null || vars.get("version").isBlank()) {
            vars.put("version", "1.0");
        }
        vars.put("page", String.valueOf(pageNumber));
        vars.put("pages", String.valueOf(pageCount));
        return vars;
    }

    public static String fill(String text, Map<String, String> vars) {
        if (text == null || text.isEmpty()) return "";
        Matcher matcher = TOKEN.matcher(text);
        StringBuffer out = new StringBuffer();
        while (matcher.find()) {
            String value = vars.get(matcher.group(1).toLowerCase(Locale.ROOT));
            if (value == null) value = vars.get(matcher.group(1));
            matcher.appendReplacement(out, Matcher.quoteReplacement(value == null ? matcher.group() : value));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    public static String text(JsonNode node, String field, String fallback) {
        if (node != null && node.hasNonNull(field) && node.get(field).isTextual()) return node.get(field).asText();
        return fallback;
    }

    private static void put(Map<String, String> vars, String key, String value) {
        if (value == null || value.isBlank()) return;
        String current = vars.get(key);
        if (current == null || current.isBlank()) vars.put(key, value);
    }

    private static String first(String preferred, String fallback) {
        if (preferred != null && !preferred.isBlank()) return preferred;
        return fallback == null ? "" : fallback;
    }
}
