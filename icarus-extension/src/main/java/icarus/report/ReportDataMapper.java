package icarus.report;

import icarus.core.Finding;
import icarus.core.ModuleConfig;
import icarus.core.Severity;
import icarus.evidence.CweRepository;
import icarus.evidence.EvidenceCapture;
import icarus.report.model.ContentPolicy;
import icarus.report.model.FindingField;
import icarus.report.render.EvidenceView;
import icarus.report.render.FindingView;
import icarus.report.render.HttpExcerpt;
import icarus.report.render.ReportData;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Transforms domain Findings and CapturedEvidence into detached render views according to ContentPolicy.
 */
public final class ReportDataMapper {

    private static final CweRepository CWE_REPO = new CweRepository();

    private ReportDataMapper() {}

    public static ReportData buildReportData(
        List<Finding> findings,
        EvidenceCapture capture,
        ModuleConfig config,
        ContentPolicy policy,
        String reportTitle,
        String projectName
    ) {
        if (findings == null) findings = Collections.emptyList();
        boolean isRetest = config != null && config.getBool("retest.enabled", false);

        // Group evidence by similarity hash
        Map<String, List<EvidenceCapture.CapturedEvidence>> evidenceByHash =
            capture != null ? capture.groupedBySimilarityHash() : Collections.emptyMap();

        Map<Severity, Long> severityCounts = new LinkedHashMap<>();
        for (Severity s : Severity.values()) {
            severityCounts.put(s, 0L);
        }

        long fixedCount = 0;
        long notFixedCount = 0;

        List<FindingView> findingViews = new ArrayList<>();
        int index = 1;

        for (Finding f : findings) {
            Severity sev = f.severity();
            severityCounts.put(sev, severityCounts.getOrDefault(sev, 0L) + 1);

            boolean isFixed = sev == Severity.FIXED;
            if (isFixed) fixedCount++;
            else if (sev == Severity.NOT_FIXED) notFixedCount++;

            // Resolve CWE label
            String cweName = "";
            String primaryCweId = "";
            if (!f.cweIds().isEmpty()) {
                primaryCweId = f.cweIds().get(0);
                var cweMatch = CWE_REPO.search(primaryCweId);
                if (!cweMatch.isEmpty()) {
                    cweName = cweMatch.get(0).name();
                }
            }

            // Map fields based on ContentPolicy
            Map<FindingField, String> fields = new EnumMap<>(FindingField.class);
            if (policy.findingFields().contains(FindingField.DESCRIPTION)) {
                fields.put(FindingField.DESCRIPTION, f.description() != null ? f.description() : "");
            }
            if (policy.findingFields().contains(FindingField.WHY) && f.metadata().containsKey("why")) {
                fields.put(FindingField.WHY, f.metadata().get("why"));
            }
            if (policy.findingFields().contains(FindingField.WHEN) && f.metadata().containsKey("when")) {
                fields.put(FindingField.WHEN, f.metadata().get("when"));
            }
            if (policy.findingFields().contains(FindingField.WHERE) && f.metadata().containsKey("where")) {
                fields.put(FindingField.WHERE, f.metadata().get("where"));
            }
            if (policy.findingFields().contains(FindingField.HOW) && f.metadata().containsKey("how")) {
                fields.put(FindingField.HOW, f.metadata().get("how"));
            }
            if (policy.findingFields().contains(FindingField.IMPACT) && f.metadata().containsKey("impact")) {
                fields.put(FindingField.IMPACT, f.metadata().get("impact"));
            }
            if (policy.findingFields().contains(FindingField.REMEDIATION) && f.metadata().containsKey("remediation")) {
                fields.put(FindingField.REMEDIATION, f.metadata().get("remediation"));
            }

            // Map evidence
            List<EvidenceView> evidenceList = new ArrayList<>();
            if (policy.includeEvidence()) {
                for (EvidenceCapture.CapturedEvidence ce : evidenceByHash.getOrDefault(f.similarityHash(), Collections.emptyList())) {
                    Path p = ce.imagePath();
                    byte[] bytes = null;
                    if (p != null && Files.exists(p)) {
                        try {
                            bytes = Files.readAllBytes(p);
                        } catch (IOException ignored) {}
                    }
                    evidenceList.add(new EvidenceView(p, ce.caption(), bytes));
                }
            }

            // Map HTTP excerpts
            HttpExcerpt reqExcerpt = HttpExcerpt.empty();
            HttpExcerpt resExcerpt = HttpExcerpt.empty();
            if (f.evidence() != null) {
                if (policy.includeHttpRequest() && f.evidence().request() != null) {
                    String rawReq = f.evidence().request().toString();
                    reqExcerpt = truncateExcerpt(rawReq, policy.maxRequestBytes());
                }
                if (policy.includeHttpResponse() && f.evidence().response() != null) {
                    String rawRes = f.evidence().response().toString();
                    resExcerpt = truncateExcerpt(rawRes, policy.maxResponseBytes());
                }
            }

            findingViews.add(new FindingView(
                f.similarityHash(),
                index++,
                f.type(),
                f.severity(),
                f.category() != null ? f.category().name() : "GENERAL",
                f.module() != null ? f.module() : "ICARUS",
                f.path() != null ? f.path() : "",
                f.cweIds(),
                cweName,
                fields,
                evidenceList,
                reqExcerpt,
                resExcerpt,
                isFixed
            ));
        }

        Map<String, String> variables = new LinkedHashMap<>();
        variables.put("finding_count", String.valueOf(findings.size()));
        variables.put("project", projectName != null ? projectName : "");
        variables.put("report_title", reportTitle != null ? reportTitle : "");

        return new ReportData(
            reportTitle,
            projectName,
            findingViews,
            severityCounts,
            fixedCount,
            notFixedCount,
            variables,
            isRetest
        );
    }

    private static HttpExcerpt truncateExcerpt(String raw, int maxBytes) {
        if (raw == null) return HttpExcerpt.empty();
        byte[] bytes = raw.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (bytes.length <= maxBytes) {
            return new HttpExcerpt(raw, false, bytes.length);
        }
        String truncatedText = new String(bytes, 0, maxBytes, java.nio.charset.StandardCharsets.UTF_8) + "\n... [TRUNCATED]";
        return new HttpExcerpt(truncatedText, true, bytes.length);
    }
}
