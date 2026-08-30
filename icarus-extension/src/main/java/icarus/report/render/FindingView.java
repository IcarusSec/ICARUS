package icarus.report.render;

import icarus.core.Severity;
import icarus.report.model.FindingField;
import java.util.*;

/**
 * Clean, detached DTO representing a finding ready for rendering.
 * All ContentPolicy filtering is applied prior to constructing this view.
 */
public record FindingView(
    String id,
    int displayIndex,
    String title,
    Severity severity,
    String category,
    String module,
    String path,
    List<String> cweIds,
    String cweName,
    Map<FindingField, String> fields,
    List<EvidenceView> evidence,
    HttpExcerpt request,
    HttpExcerpt response,
    boolean isRetestFixed
) {
    public FindingView {
        if (cweIds == null) cweIds = Collections.emptyList();
        if (fields == null) fields = Collections.emptyMap();
        if (evidence == null) evidence = Collections.emptyList();
        if (request == null) request = HttpExcerpt.empty();
        if (response == null) response = HttpExcerpt.empty();
    }

    public String getField(FindingField field) {
        return fields.getOrDefault(field, "");
    }
}
