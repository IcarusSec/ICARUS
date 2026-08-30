package icarus.report.render;

import icarus.core.Severity;
import java.util.*;

/**
 * Aggregated report dataset ready for consumption by PDF and HTML generators.
 */
public record ReportData(
    String reportTitle,
    String projectName,
    List<FindingView> findings,
    Map<Severity, Long> severityCounts,
    long fixedCount,
    long notFixedCount,
    Map<String, String> variables,
    boolean isRetest
) {
    public ReportData {
        if (findings == null) findings = Collections.emptyList();
        if (severityCounts == null) severityCounts = Collections.emptyMap();
        if (variables == null) variables = Collections.emptyMap();
    }

    public long getCount(Severity severity) {
        return severityCounts.getOrDefault(severity, 0L);
    }
}
