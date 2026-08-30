package icarus.report;

import icarus.core.ReportTemplateConfig;
import icarus.report.model.*;
import java.util.*;

/**
 * Migrates legacy single-instance ReportTemplateConfig into a user-editable ReportProfile.
 */
public final class ReportTemplateConfigMigrator {

    private ReportTemplateConfigMigrator() {}

    public static ReportProfile migrate(ReportTemplateConfig oldConfig, ReportProfile baseProfile) {
        if (oldConfig == null) return null;
        if (baseProfile == null) {
            throw new IllegalArgumentException("Base profile required for migration");
        }

        String primary = oldConfig.primaryColor() != null ? oldConfig.primaryColor() : baseProfile.pdfTheme().primaryHex();
        String secondary = oldConfig.secondaryColor() != null ? oldConfig.secondaryColor() : baseProfile.pdfTheme().secondaryHex();
        boolean dark = "dark".equalsIgnoreCase(oldConfig.themeName());

        PdfTheme pdfTheme = new PdfTheme(
            primary,
            secondary,
            baseProfile.pdfTheme().textHex(),
            primary,
            baseProfile.pdfTheme().tableHeaderHex(),
            baseProfile.pdfTheme().severityHex(),
            baseProfile.pdfTheme().fontStack(),
            baseProfile.pdfTheme().baseFontSize(),
            baseProfile.pdfTheme().pageBox()
        );

        HtmlTheme htmlTheme = new HtmlTheme(
            primary,
            secondary,
            dark ? "#1A1A1A" : "#FFFFFF",
            dark ? "#262626" : "#F7F7F7",
            dark ? "#E8E8E8" : "#1A1A1A",
            dark ? "#3A3A3A" : "#DDDDDD",
            baseProfile.htmlTheme().severityHex(),
            baseProfile.htmlTheme().fontStack(),
            dark
        );

        Map<String, String> vars = oldConfig.variables() != null ? oldConfig.variables() : Collections.emptyMap();
        BrandingConfig branding = new BrandingConfig(
            oldConfig.logoPath(),
            oldConfig.clientLogoPath(),
            vars.getOrDefault("author", ""),
            vars.getOrDefault("reviewer", ""),
            vars.getOrDefault("approver", ""),
            vars.getOrDefault("classification", "Confidencial"),
            vars.getOrDefault("environment", ""),
            vars.getOrDefault("report_title", "Relatório de Teste de Intrusão"),
            vars.getOrDefault("team", ""),
            vars.getOrDefault("component", ""),
            vars.getOrDefault("requester", ""),
            vars.getOrDefault("owner", ""),
            vars.getOrDefault("assessment_period", ""),
            vars.getOrDefault("method", "")
        );

        ContentPolicy content = new ContentPolicy(
            true, true, true, 4096, 4096,
            List.of(FindingField.values()),
            CweMode.HARDCODED_CATALOG,
            Collections.emptyList(),
            oldConfig.tocEnabled()
        );

        // Map sections if present
        List<SectionNode> nodes = new ArrayList<>();
        if (oldConfig.sections() != null && !oldConfig.sections().isEmpty()) {
            int order = 1;
            for (ReportTemplateConfig.Section s : oldConfig.sections()) {
                String id = mapLegacyTitleToSectionId(s.title());
                boolean required = ReportTemplateConfig.FINDINGS_MARKER.equalsIgnoreCase(s.title());
                nodes.add(SectionNode.of(id, true, order++, required, id, Collections.emptyMap()));
            }
        } else {
            nodes = baseProfile.sections().nodes();
        }

        return new ReportProfile(
            ReportProfile.CURRENT_SCHEMA_VERSION,
            "migrated-default",
            "Migrated Profile",
            "pt-BR",
            false,
            baseProfile.id(),
            baseProfile.coverRenderer(),
            baseProfile.findingRenderer(),
            new SectionGraph(nodes),
            branding,
            content,
            pdfTheme,
            htmlTheme
        );
    }

    private static String mapLegacyTitleToSectionId(String title) {
        if (title == null) return "CUSTOM_SECTION";
        if (title.equalsIgnoreCase(ReportTemplateConfig.FINDINGS_MARKER)) return "FINDINGS";
        if (title.equalsIgnoreCase(ReportTemplateConfig.VULNERABILITY_SUMMARY_MARKER)) return "VULNERABILITY_SUMMARY";
        String normalized = title.toUpperCase()
            .replaceAll("[^A-Z0-9_]", "_")
            .replaceAll("_+", "_");
        if (normalized.startsWith("_")) normalized = normalized.substring(1);
        if (normalized.endsWith("_")) normalized = normalized.substring(0, normalized.length() - 1);
        return normalized.isEmpty() ? "SECTION" : normalized;
    }
}
