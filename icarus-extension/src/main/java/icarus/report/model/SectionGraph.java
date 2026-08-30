package icarus.report.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Ordered collection of report section nodes.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SectionGraph(
    @JsonProperty("nodes") List<SectionNode> nodes
) {
    public SectionGraph {
        if (nodes == null) nodes = Collections.emptyList();
    }

    /**
     * Returns enabled section nodes sorted by their defined order.
     */
    public List<SectionNode> enabledInOrder() {
        return nodes.stream()
            .filter(SectionNode::enabled)
            .sorted(Comparator.comparingInt(SectionNode::order))
            .collect(Collectors.toList());
    }

    public Optional<SectionNode> findNode(String id) {
        if (id == null) return Optional.empty();
        return nodes.stream().filter(n -> id.equalsIgnoreCase(n.id())).findFirst();
    }

    public boolean isEnabled(String id) {
        return findNode(id).map(SectionNode::enabled).orElse(false);
    }
}
