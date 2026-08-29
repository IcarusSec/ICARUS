package icarus.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Modular report configuration: custom sections, template variables, theme,
 * and retest settings. Persisted as JSON under {@link ModuleConfig#REPORT_TEMPLATE_CONFIG_KEY}
 * (schema documented in report_improvement_steps/02_moduleconfig_json_migration.md).
 */
public final class ReportTemplateConfig {

    public record Section(String title, String content) {}
    public record FindingTemplate(String descricao, String impacto, String recomendacao, String severidade) {}

    /** Sentinel title marking where the Vulnerability Summary table renders. */
    public static final String VULNERABILITY_SUMMARY_MARKER = "VULNERABILITY_SUMMARY";

    /** Sentinel title marking where the Findings block (summary + finding cards) renders
     *  among the custom sections. If no section carries this title, Findings render last,
     *  after all custom sections — the historical default. */
    public static final String FINDINGS_MARKER = "FINDINGS";

    /** Ships built in so a fresh install already produces a complete report with zero setup;
     *  still fully editable from Settings → Reporting, same as any user-added section. */
    private static List<Section> defaultSections() {
        return List.of(
            // {{finding_count}} and {{finding_types}} aren't user-editable variables --
            // they're computed fresh from the actual findings list at generate time
            // (PdfReportGenerator/ReportGenerator.generate(), right after loading this config)
            // and injected into a copy of variables() before interpolation, never persisted.
            new Section(I18n.t("report.template.title.resumo_executivo"), I18n.t("report.template.content.resumo_executivo")),
            new Section(I18n.t("report.template.title.controle_documento"), I18n.t("report.template.content.controle_documento")),
            // Free-text bullet history -- no per-row date/version fields, just a running log the
            // team edits by hand each revision cycle. Not in MANDATORY_SECTION_TITLES: existing
            // configs won't get it force-appended, only fresh installs ship with it.
            new Section(I18n.t("report.template.title.historico_revisoes"), I18n.t("report.template.content.historico_revisoes")),
            new Section(I18n.t("report.template.title.escopo_avaliacao"), I18n.t("report.template.content.escopo_avaliacao")),
            // Same non-mandatory treatment as "Histórico de Revisões" above.
            new Section(I18n.t("report.template.title.usuarios_perfis_acesso"), I18n.t("report.template.content.usuarios_perfis_acesso")),
            // PDF generation renders this section's real content as three native tables
            // (PdfReportGenerator.appendRiskMatrixBody, matched by this exact title) instead of
            // this Markdown fallback -- MarkdownPdfRenderer doesn't support Markdown tables, so
            // this bullet form only actually renders as-is in the HTML report, or if the PDF's
            // title match is broken by renaming the section.
            new Section(I18n.t("report.template.title.matriz_classificacao_cvss4"), I18n.t("report.template.content.matriz_classificacao_cvss4")),
            new Section(I18n.t("report.template.title.referencias_tecnicas"), I18n.t("report.template.content.referencias_tecnicas")),
            // No content — this is a placement marker; appendSections() (both generators)
            // special-cases this exact title to render the Findings summary + finding cards
            // in its place instead of Markdown content. Mandatory so a report always has a
            // Findings section somewhere; historical default is last, after all custom sections.
            new Section(FINDINGS_MARKER, ""),
            new Section(I18n.t("report.template.title.relacao_vulnerabilidades"), VULNERABILITY_SUMMARY_MARKER)
        );
    }

    /** These must ship in every report regardless of what's been edited/imported/removed
     *  in Settings → Reporting's section list — enforced in {@link #sections()} itself (not
     *  just at generation time) so every reader of this config, not only the two report
     *  generators, sees the guarantee. */
    private static final List<String> MANDATORY_SECTION_TITLES = List.of(
        I18n.t("report.template.title.resumo_executivo"), I18n.t("report.template.title.controle_documento"), I18n.t("report.template.title.escopo_avaliacao"),
        I18n.t("report.template.title.matriz_classificacao_cvss4"), I18n.t("report.template.title.referencias_tecnicas"), I18n.t("report.template.title.relacao_vulnerabilidades"), FINDINGS_MARKER
    );

    public static boolean isMandatory(String sectionTitle) {
        return MANDATORY_SECTION_TITLES.contains(sectionTitle);
    }

    private static Map<String, String> defaultVariables() {
        Map<String, String> vars = new LinkedHashMap<>();
        for (String key : List.of("classification", "report_title", "project", "version", "date", "author", "reviewer",
                "approver", "team", "component", "requester", "owner", "environment", "assessment_period", "method")) {
            vars.put(key, "");
        }
        return vars;
    }

    /** Renamed English variable key -> legacy pt-BR key it replaces, so a project saved before
     *  this rename still surfaces its filled-in values under the new key (see {@link #fromConfig}). */
    private static final Map<String, String> LEGACY_VARIABLE_KEY_ALIASES = Map.ofEntries(
        Map.entry("classification", "classificacao"), Map.entry("report_title", "titulo_relatorio"),
        Map.entry("project", "projeto"), Map.entry("version", "versao"), Map.entry("date", "data"),
        Map.entry("author", "autor"), Map.entry("reviewer", "revisor"), Map.entry("approver", "aprovador"),
        Map.entry("team", "eht"), Map.entry("component", "componente"), Map.entry("requester", "solicitante"),
        Map.entry("owner", "responsavel"), Map.entry("environment", "ambiente"),
        Map.entry("assessment_period", "periodo_avaliacao"), Map.entry("method", "metodo")
    );

    // Neutral default accent — no client branding baked in. Fully overridable from
    // Settings → Reporting → Theme.
    private static final String DEFAULT_PRIMARY_COLOR = "#3E7BB8";
    private static final String DEFAULT_SECONDARY_COLOR = "#6E6E6E";

    private List<Section> sections = new ArrayList<>();
    private Map<String, String> variables = new LinkedHashMap<>();
    private Map<String, FindingTemplate> findingTemplates = new LinkedHashMap<>();
    private String primaryColor;
    private String secondaryColor;
    private String customCssPath;
    private String logoPath;
    private String clientLogoPath;
    private String themeName = "light"; // "light" or "dark" — HTML report base theme
    private List<String> retestStatuses = new ArrayList<>();
    private List<String> retestSuppressedSections = new ArrayList<>();
    private boolean tocEnabled = true;

    /** Guarantees {@link #MANDATORY_SECTION_TITLES} are present, appending each (with its
     *  built-in default content) if missing — e.g. removed via Settings' "Remove" button, or a
     *  hand-edited/older import missing one. Doesn't touch what's actually persisted (that only
     *  happens via {@link #saveTo}); a subsequent edit-and-save re-derives from this same
     *  guarantee, so a mandatory section can't be permanently dropped by editing around it. */
    public synchronized List<Section> sections() {
        boolean allPresent = MANDATORY_SECTION_TITLES.stream()
                .allMatch(title -> sections.stream().anyMatch(s -> s.title().equals(title)));
        if (allPresent) return Collections.unmodifiableList(sections);

        Map<String, Section> defaultsByTitle = defaultSections().stream()
                .collect(Collectors.toMap(Section::title, s -> s));
        List<Section> healed = new ArrayList<>(sections);
        for (String title : MANDATORY_SECTION_TITLES) {
            if (healed.stream().noneMatch(s -> s.title().equals(title))) {
                healed.add(defaultsByTitle.get(title));
            }
        }
        return Collections.unmodifiableList(healed);
    }

    public synchronized void setSections(List<Section> newSections) {
        if (newSections == null) return;
        this.sections = new ArrayList<>(newSections);
    }

    /**
     * Adds a section at a specified 0-based index, or appends it to the end if index is null/out of bounds.
     */
    public synchronized void addSection(String title, String content, Integer index) {
        if (title == null || title.isBlank()) return;
        Section newSec = new Section(title, content != null ? content : "");
        if (index == null || index < 0 || index >= sections.size()) {
            sections.add(newSec);
        } else {
            sections.add(index, newSec);
        }
    }

    /**
     * Removes a section by title. Returns false if the section is mandatory or not found.
     */
    public synchronized boolean removeSection(String title) {
        if (title == null || isMandatory(title)) {
            return false; // Mandatory sections cannot be deleted
        }
        return sections.removeIf(s -> s.title().equalsIgnoreCase(title.trim()));
    }

    /**
     * Updates an existing section's content and/or title by matching title.
     */
    public synchronized boolean updateSection(String title, String newTitle, String newContent) {
        if (title == null) return false;
        for (int i = 0; i < sections.size(); i++) {
            Section s = sections.get(i);
            if (s.title().equalsIgnoreCase(title.trim())) {
                String finalTitle = (newTitle != null && !newTitle.isBlank() && !isMandatory(s.title()))
                        ? newTitle : s.title();
                String finalContent = newContent != null ? newContent : s.content();
                sections.set(i, new Section(finalTitle, finalContent));
                return true;
            }
        }
        return false;
    }

    public synchronized Map<String, String> variables() { return variables; }
    public synchronized void setVariables(Map<String, String> variables) { this.variables = new LinkedHashMap<>(variables); }

    public Map<String, FindingTemplate> findingTemplates() { return findingTemplates; }
    public void setFindingTemplates(Map<String, FindingTemplate> findingTemplates) { this.findingTemplates = new LinkedHashMap<>(findingTemplates); }

    public FindingTemplate getFindingTemplate(String type) {
        FindingTemplate tmpl = findingTemplates.get(type);
        if (tmpl != null) {
            return tmpl;
        }
        
        KnowledgeBaseEntry kbEntry = VulnerabilityKnowledgeBase.getInstance().getEntry(type);
        if (kbEntry != null) {
            String severity = mapRiscoToSeverity(kbEntry.severity());
            return new FindingTemplate(kbEntry.description(), kbEntry.impact(), kbEntry.recommendation(), severity);
        }
        
        return null;
    }

    private String mapRiscoToSeverity(String risco) {
        if (risco == null) return "INFO";
        return switch (risco.toLowerCase()) {
            case "critical", "extremo", "crítico", "critico" -> "CRITICAL";
            case "high", "alto" -> "HIGH";
            case "medium", "médio", "medio" -> "MEDIUM";
            case "low", "baixo" -> "LOW";
            default -> "INFO";
        };
    }

    public String primaryColor() { return primaryColor; }
    public void setPrimaryColor(String primaryColor) { this.primaryColor = primaryColor; }

    public String secondaryColor() { return secondaryColor; }
    public void setSecondaryColor(String secondaryColor) { this.secondaryColor = secondaryColor; }

    public String customCssPath() { return customCssPath; }
    public void setCustomCssPath(String customCssPath) { this.customCssPath = customCssPath; }

    public String logoPath() { return logoPath; }
    public void setLogoPath(String logoPath) { this.logoPath = logoPath; }

    public String clientLogoPath() { return clientLogoPath; }
    public void setClientLogoPath(String clientLogoPath) { this.clientLogoPath = clientLogoPath; }

    public String themeName() { return themeName; }
    public void setThemeName(String themeName) { this.themeName = themeName; }

    public List<String> retestStatuses() { return retestStatuses; }
    public void setRetestStatuses(List<String> retestStatuses) { this.retestStatuses = new ArrayList<>(retestStatuses); }

    public List<String> retestSuppressedSections() { return retestSuppressedSections; }
    public void setRetestSuppressedSections(List<String> s) { this.retestSuppressedSections = new ArrayList<>(s); }

    public boolean tocEnabled() { return tocEnabled; }
    public void setTocEnabled(boolean tocEnabled) { this.tocEnabled = tocEnabled; }

    public static List<String> defaultRetestStatuses() {
        return List.of("FIXED", "NOT_FIXED");
    }

    public static List<String> defaultRetestSuppressedSections() {
        return List.of(
            I18n.t("report.template.title.matriz_classificacao_cvss4", "Risk Classification Matrix (CVSS 4)"),
            I18n.t("report.template.title.referencias_tecnicas", "Technical References"),
            I18n.t("report.template.title.usuarios_perfis_acesso", "Users and Access Profiles")
        );
    }

    /** Loads from {@code config}'s JSON blob, or returns migration/hardcoded defaults if unset/unparseable. */
    @SuppressWarnings("unchecked")
    public static ReportTemplateConfig fromConfig(ModuleConfig config) {
        Object raw = config.getJson(ModuleConfig.REPORT_TEMPLATE_CONFIG_KEY);
        ReportTemplateConfig result = new ReportTemplateConfig();
        if (!(raw instanceof Map<?, ?> rawMap)) {
            result.retestStatuses = new ArrayList<>(defaultRetestStatuses());
            result.retestSuppressedSections = new ArrayList<>(defaultRetestSuppressedSections());
            result.sections = defaultSections();
            result.variables = defaultVariables();
            result.primaryColor = DEFAULT_PRIMARY_COLOR;
            result.secondaryColor = DEFAULT_SECONDARY_COLOR;
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
        result.sections = sections.isEmpty() ? defaultSections() : sections;

        Map<String, Object> rawVars = (Map<String, Object>) map.getOrDefault("variables", Map.of());
        Map<String, String> vars = new LinkedHashMap<>();
        rawVars.forEach((k, v) -> vars.put(k, String.valueOf(v)));
        // A project saved before the pt-BR -> English variable-key rename still has its values
        // under the old key ("autor", not "author"); surface them under the new key too so a
        // section referencing {{author}} doesn't come up blank for existing users.
        LEGACY_VARIABLE_KEY_ALIASES.forEach((newKey, oldKey) -> {
            if ((!vars.containsKey(newKey) || vars.get(newKey).isBlank()) && vars.containsKey(oldKey)) {
                vars.put(newKey, vars.get(oldKey));
            }
        });
        result.variables = vars.isEmpty() ? defaultVariables() : vars;

        Map<String, Object> theme = (Map<String, Object>) map.getOrDefault("theme", Map.of());
        String primary = stringOrNull(theme.get("primaryColor"));
        String secondary = stringOrNull(theme.get("secondaryColor"));
        result.primaryColor = primary != null ? primary : DEFAULT_PRIMARY_COLOR;
        result.secondaryColor = secondary != null ? secondary : DEFAULT_SECONDARY_COLOR;
        result.customCssPath = stringOrNull(theme.get("customCssPath"));
        result.logoPath = stringOrNull(theme.get("logoPath"));
        result.clientLogoPath = stringOrNull(theme.get("clientLogoPath"));
        String themeNameRaw = stringOrNull(theme.get("themeName"));
        result.themeName = themeNameRaw != null ? themeNameRaw : "light";

        List<String> rStatuses = ((List<Object>) map.getOrDefault("retestStatuses", List.of()))
                .stream().map(String::valueOf).collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        result.retestStatuses = rStatuses.isEmpty() ? defaultRetestStatuses() : rStatuses;

        List<String> rSuppressed = ((List<Object>) map.getOrDefault("retestSuppressedSections", List.of()))
                .stream().map(String::valueOf).collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        result.retestSuppressedSections = rSuppressed.isEmpty() ? defaultRetestSuppressedSections() : rSuppressed;
        result.tocEnabled = Boolean.TRUE.equals(map.getOrDefault("tocEnabled", true));

        Map<String, Object> rawTemplates = (Map<String, Object>) map.getOrDefault("findingTemplates", Map.of());
        Map<String, FindingTemplate> templates = new LinkedHashMap<>();
        rawTemplates.forEach((k, v) -> {
            if (v instanceof Map<?, ?> tmplMap) {
                templates.put(k, new FindingTemplate(
                    stringOrNull(tmplMap.get("descricao")),
                    stringOrNull(tmplMap.get("impacto")),
                    stringOrNull(tmplMap.get("recomendacao")),
                    stringOrNull(tmplMap.get("severidade"))
                ));
            }
        });
        result.findingTemplates = templates;

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
        theme.put("logoPath", logoPath);
        theme.put("clientLogoPath", clientLogoPath);
        theme.put("themeName", themeName);

        Map<String, Object> templatesJson = new LinkedHashMap<>();
        findingTemplates.forEach((k, v) -> {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("descricao", v.descricao());
            m.put("impacto", v.impacto());
            m.put("recomendacao", v.recomendacao());
            m.put("severidade", v.severidade());
            templatesJson.put(k, m);
        });

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("sections", sectionsJson);
        root.put("variables", new LinkedHashMap<Object, Object>(variables));
        root.put("findingTemplates", templatesJson);
        root.put("theme", theme);
        root.put("retestStatuses", new ArrayList<Object>(retestStatuses));
        root.put("retestSuppressedSections", new ArrayList<Object>(retestSuppressedSections));
        root.put("tocEnabled", tocEnabled);

        config.setJson(ModuleConfig.REPORT_TEMPLATE_CONFIG_KEY, root);
    }

    /** Adds {{finding_count}} and {{finding_types}} to this instance's variables -- in memory
     *  only, never persisted via {@link #saveTo} -- so "Executive Summary" (or any section) can
     *  reference the actual findings list. Call once per report generation, right after
     *  {@link #fromConfig}, before rendering any section. */
    public void injectFindingsVariables(List<Finding> findings) {
        variables.put("finding_count", String.valueOf(findings.size()));
        variables.put("finding_types", findings.stream().map(Finding::type).collect(Collectors.joining(", ")));
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
