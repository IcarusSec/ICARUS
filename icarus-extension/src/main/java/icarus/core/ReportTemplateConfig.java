package icarus.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Modular report configuration: custom sections, template variables, theme,
 * and retest settings. Persisted as JSON under {@link ModuleConfig#REPORT_TEMPLATE_CONFIG_KEY}
 * (schema documented in report_improvement_steps/02_moduleconfig_json_migration.md).
 */
public final class ReportTemplateConfig {

    public record Section(String title, String content) {}

    private List<Section> sections = new ArrayList<>();
    private Map<String, String> variables = new LinkedHashMap<>();
    private String primaryColor;
    private String secondaryColor;
    private String customCssPath;
    private String themeName = "light"; // "light" or "dark" — HTML report base theme
    private List<String> retestStatuses = new ArrayList<>();
    private List<String> retestSuppressedSections = new ArrayList<>();
    private boolean tocEnabled = true;

    public List<Section> sections() { return sections; }
    public void setSections(List<Section> sections) { this.sections = new ArrayList<>(sections); }

    public Map<String, String> variables() { return variables; }
    public void setVariables(Map<String, String> variables) { this.variables = new LinkedHashMap<>(variables); }

    public String primaryColor() { return primaryColor; }
    public void setPrimaryColor(String primaryColor) { this.primaryColor = primaryColor; }

    public String secondaryColor() { return secondaryColor; }
    public void setSecondaryColor(String secondaryColor) { this.secondaryColor = secondaryColor; }

    public String customCssPath() { return customCssPath; }
    public void setCustomCssPath(String customCssPath) { this.customCssPath = customCssPath; }

    public String themeName() { return themeName; }
    public void setThemeName(String themeName) { this.themeName = themeName; }

    public List<String> retestStatuses() { return retestStatuses; }
    public void setRetestStatuses(List<String> retestStatuses) { this.retestStatuses = new ArrayList<>(retestStatuses); }

    public List<String> retestSuppressedSections() { return retestSuppressedSections; }
    public void setRetestSuppressedSections(List<String> s) { this.retestSuppressedSections = new ArrayList<>(s); }

    public boolean tocEnabled() { return tocEnabled; }
    public void setTocEnabled(boolean tocEnabled) { this.tocEnabled = tocEnabled; }

    /** Loads from {@code config}'s JSON blob, or returns migration/hardcoded defaults if unset/unparseable. */
    @SuppressWarnings("unchecked")
    public static ReportTemplateConfig fromConfig(ModuleConfig config) {
        Object raw = config.getJson(ModuleConfig.REPORT_TEMPLATE_CONFIG_KEY);
        ReportTemplateConfig result = new ReportTemplateConfig();
        if (!(raw instanceof Map<?, ?> rawMap)) {
            result.retestStatuses = new ArrayList<>(List.of("Fixed", "Not Fixed"));
            return result;
        }
        Map<String, Object> map = (Map<String, Object>) rawMap;

        List<Object> rawSections = (List<Object>) map.getOrDefault("sections", List.of());
        List<Section> sections = new ArrayList<>();
        List<Map<String, Object>> sorted = new ArrayList<>();
        for (Object o : rawSections) sorted.add((Map<String, Object>) o);
        sorted.sort((a, b) -> Double.compare(numberOf(a.get("order")), numberOf(b.get("order"))));
        for (Map<String, Object> s : sorted) {
            sections.add(new Section(String.valueOf(s.getOrDefault("title", "")), String.valueOf(s.getOrDefault("content", ""))));
        }
        result.sections = sections;

        Map<String, Object> rawVars = (Map<String, Object>) map.getOrDefault("variables", Map.of());
        Map<String, String> vars = new LinkedHashMap<>();
        rawVars.forEach((k, v) -> vars.put(k, String.valueOf(v)));
        result.variables = vars;

        Map<String, Object> theme = (Map<String, Object>) map.getOrDefault("theme", Map.of());
        result.primaryColor = stringOrNull(theme.get("primaryColor"));
        result.secondaryColor = stringOrNull(theme.get("secondaryColor"));
        result.customCssPath = stringOrNull(theme.get("customCssPath"));
        String themeNameRaw = stringOrNull(theme.get("themeName"));
        result.themeName = themeNameRaw != null ? themeNameRaw : "light";

        result.retestStatuses = ((List<Object>) map.getOrDefault("retestStatuses", List.of()))
                .stream().map(String::valueOf).collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        result.retestSuppressedSections = ((List<Object>) map.getOrDefault("retestSuppressedSections", List.of()))
                .stream().map(String::valueOf).collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        result.tocEnabled = Boolean.TRUE.equals(map.getOrDefault("tocEnabled", true));

        return result;
    }

    private static double numberOf(Object o) {
        return o instanceof Number n ? n.doubleValue() : 0;
    }

    private static String stringOrNull(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    /** Serializes this config back into {@code config}'s JSON blob. */
    public void saveTo(ModuleConfig config) {
        List<Object> sectionsJson = new ArrayList<>();
        for (int i = 0; i < sections.size(); i++) {
            Section s = sections.get(i);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("title", s.title());
            m.put("content", s.content());
            m.put("order", (double) i);
            sectionsJson.add(m);
        }

        Map<String, Object> theme = new LinkedHashMap<>();
        theme.put("primaryColor", primaryColor);
        theme.put("secondaryColor", secondaryColor);
        theme.put("customCssPath", customCssPath);
        theme.put("themeName", themeName);

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("sections", sectionsJson);
        root.put("variables", new LinkedHashMap<Object, Object>(variables));
        root.put("theme", theme);
        root.put("retestStatuses", new ArrayList<Object>(retestStatuses));
        root.put("retestSuppressedSections", new ArrayList<Object>(retestSuppressedSections));
        root.put("tocEnabled", tocEnabled);

        config.setJson(ModuleConfig.REPORT_TEMPLATE_CONFIG_KEY, root);
    }

    /** Interpolates {@code {{key}}} placeholders in {@code text} using {@link #variables()}. Unknown vars render empty. */
    public String interpolate(String text) {
        if (text == null) return "";
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < text.length()) {
            int start = text.indexOf("{{", i);
            if (start < 0) { out.append(text, i, text.length()); break; }
            out.append(text, i, start);
            int end = text.indexOf("}}", start + 2);
            if (end < 0) { out.append(text, start, text.length()); break; }
            String key = text.substring(start + 2, end).trim();
            out.append(variables.getOrDefault(key, ""));
            i = end + 2;
        }
        return out.toString();
    }
}
